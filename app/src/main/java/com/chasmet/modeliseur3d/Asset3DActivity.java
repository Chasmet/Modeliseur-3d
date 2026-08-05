package com.chasmet.modeliseur3d;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.assets.Asset3DAdapter;
import com.chasmet.modeliseur3d.assets.Asset3DCatalog;
import com.chasmet.modeliseur3d.assets.Asset3DDownloader;
import com.chasmet.modeliseur3d.assets.Asset3DItem;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Troisième onglet : catalogue d'assets GLB libres, ouvrables et exportables. */
public final class Asset3DActivity extends AppCompatActivity
        implements Asset3DAdapter.ActionListener {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> exportDocumentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("model/gltf-binary"),
                    this::onExportDocumentSelected
            );

    private Spinner categorySpinner;
    private ListView list;
    private ProgressBar progress;
    private TextView status;
    private Asset3DAdapter adapter;
    private boolean busy;
    private File pendingExportFile;
    private Asset3DItem pendingExportItem;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_asset_3d);

        categorySpinner = findViewById(R.id.assetCategorySpinner);
        list = findViewById(R.id.assetListView);
        progress = findViewById(R.id.assetProgressBar);
        status = findViewById(R.id.assetStatusText);

        ArrayAdapter<String> categories = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                Asset3DCatalog.categories()
        );
        categories.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(categories);
        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
            ) {
                showCategory((String) parent.getItemAtPosition(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                showCategory(Asset3DCatalog.ALL);
            }
        });
        showCategory(Asset3DCatalog.ALL);
    }

    private void showCategory(String category) {
        List<Asset3DItem> items = Asset3DCatalog.filter(category);
        adapter = new Asset3DAdapter(this, items, this);
        list.setAdapter(adapter);
        if (Asset3DCatalog.ALL.equals(category)) {
            status.setText(items.size()
                    + " assets libres • " + Asset3DCatalog.countAnimated()
                    + " animés • " + Asset3DCatalog.countGenerated()
                    + " générés hors ligne • ouvrir ou exporter en GLB");
        } else {
            status.setText(items.size() + " assets dans « " + category
                    + " » • bouton Exporter disponible sur chaque modèle");
        }
    }

    @Override
    public void onAssetAction(Asset3DItem item) {
        if (busy) {
            return;
        }
        try {
            File existing = Asset3DDownloader.fileFor(this, item);
            if (Asset3DDownloader.isDownloaded(this, item)) {
                openAsset(existing, item);
                return;
            }
        } catch (Exception ignored) {
            // L'opération affichera une erreur plus précise si le stockage manque.
        }
        prepare(item, NextAction.OPEN);
    }

    @Override
    public void onExport(Asset3DItem item) {
        if (busy) {
            return;
        }
        try {
            File existing = Asset3DDownloader.fileFor(this, item);
            if (Asset3DDownloader.isDownloaded(this, item)) {
                beginExport(existing, item);
                return;
            }
        } catch (Exception ignored) {
            // La préparation affichera une erreur détaillée.
        }
        prepare(item, NextAction.EXPORT);
    }

    @Override
    public void onSource(Asset3DItem item) {
        if (item.isGenerated()) {
            Toast.makeText(
                    this,
                    "Asset créé hors ligne par l'application • licence CC0 1.0 • utilisable et modifiable librement.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(item.getSourceUrl()));
        try {
            startActivity(browser);
        } catch (Exception error) {
            Toast.makeText(
                    this,
                    "Aucun navigateur disponible.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void prepare(Asset3DItem item, NextAction nextAction) {
        String start = item.isGenerated()
                ? "Création locale de " + item.getName() + "…"
                : "Téléchargement de " + item.getName() + "…";
        setBusy(true, start);
        worker.execute(() -> {
            try {
                File file = Asset3DDownloader.download(
                        this,
                        item,
                        (downloaded, total) -> runOnUiThread(() -> {
                            if (item.isGenerated()) {
                                status.setText("Génération du GLB texturé : "
                                        + item.getName() + "…");
                            } else {
                                String text = "Téléchargement " + item.getName()
                                        + " • " + Asset3DDownloader.formatBytes(downloaded);
                                if (total > 0L) {
                                    text += " / " + Asset3DDownloader.formatBytes(total);
                                }
                                status.setText(text);
                            }
                        })
                );
                runOnUiThread(() -> {
                    String verb = item.isGenerated() ? "généré" : "téléchargé";
                    setBusy(false, item.getName() + " " + verb + " : "
                            + Asset3DDownloader.formatBytes(file.length()));
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    if (nextAction == NextAction.EXPORT) {
                        beginExport(file, item);
                    } else {
                        openAsset(file, item);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    String detail = error.getMessage() == null
                            ? "erreur inconnue"
                            : error.getMessage();
                    setBusy(false, "Création impossible : " + detail);
                    Toast.makeText(
                            this,
                            "Asset non préparé : " + detail,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void beginExport(File file, Asset3DItem item) {
        if (file == null || !file.isFile()) {
            Toast.makeText(this, "Le GLB à exporter est introuvable.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingExportFile = file;
        pendingExportItem = item;
        status.setText("Choisis le dossier et le nom du fichier GLB…");
        exportDocumentLauncher.launch(exportFileName(item));
    }

    private void onExportDocumentSelected(Uri destination) {
        if (destination == null) {
            pendingExportFile = null;
            pendingExportItem = null;
            if (status != null) {
                status.setText("Export annulé. Le GLB reste disponible dans l'application.");
            }
            return;
        }
        File source = pendingExportFile;
        Asset3DItem item = pendingExportItem;
        pendingExportFile = null;
        pendingExportItem = null;
        if (source == null || item == null || !source.isFile()) {
            Toast.makeText(this, "Le fichier source n'est plus disponible.", Toast.LENGTH_LONG).show();
            return;
        }
        setBusy(true, "Export de " + item.getName() + "…");
        worker.execute(() -> {
            try {
                copyToDocument(source, destination);
                runOnUiThread(() -> {
                    setBusy(false, item.getName() + " exporté en GLB • "
                            + Asset3DDownloader.formatBytes(source.length()));
                    Toast.makeText(
                            this,
                            "GLB exporté avec succès.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    String detail = error.getMessage() == null
                            ? "écriture impossible"
                            : error.getMessage();
                    setBusy(false, "Export impossible : " + detail);
                    Toast.makeText(
                            this,
                            "Le GLB n'a pas été exporté : " + detail,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void copyToDocument(File source, Uri destination) throws IOException {
        OutputStream rawOutput = getContentResolver().openOutputStream(destination, "w");
        if (rawOutput == null) {
            throw new IOException("Le dossier choisi refuse l'écriture");
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(rawOutput)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static String exportFileName(Asset3DItem item) {
        String normalized = Normalizer.normalize(
                item.getName(),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "");
        String safe = normalized.replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isEmpty()) {
            safe = item.getId();
        }
        return safe + ".glb";
    }

    private void openAsset(File file, Asset3DItem item) {
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );
        Intent viewer = new Intent(Intent.ACTION_VIEW);
        viewer.setDataAndType(uri, "model/gltf-binary");
        viewer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewer.setClipData(ClipData.newRawUri("Asset GLB", uri));
        try {
            startActivity(viewer);
            status.setText(item.getName() + " ouvert • "
                    + Asset3DDownloader.formatBytes(file.length())
                    + (item.isAnimated() ? " • animation intégrée" : ""));
        } catch (Exception unavailable) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("model/gltf-binary");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, item.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("Asset GLB", uri));
            startActivity(Intent.createChooser(
                    share,
                    "Ouvrir ou enregistrer l'asset 3D"
            ));
        }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        categorySpinner.setEnabled(!value);
        list.setEnabled(!value);
        status.setText(message);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private enum NextAction {
        OPEN,
        EXPORT
    }
}

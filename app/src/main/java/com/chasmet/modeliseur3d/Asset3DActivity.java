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

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.chasmet.modeliseur3d.assets.Asset3DAdapter;
import com.chasmet.modeliseur3d.assets.Asset3DCatalog;
import com.chasmet.modeliseur3d.assets.Asset3DDownloader;
import com.chasmet.modeliseur3d.assets.Asset3DItem;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Troisième onglet : catalogue d'assets GLB libres et prêts à télécharger. */
public final class Asset3DActivity extends AppCompatActivity
        implements Asset3DAdapter.ActionListener {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Spinner categorySpinner;
    private ListView list;
    private ProgressBar progress;
    private TextView status;
    private Asset3DAdapter adapter;
    private boolean busy;

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
        status.setText(items.size()
                + " assets disponibles • téléchargement direct • limite stricte 8 Mo");
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
            // Le téléchargement affichera une erreur plus précise si le stockage manque.
        }
        download(item);
    }

    @Override
    public void onSource(Asset3DItem item) {
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

    private void download(Asset3DItem item) {
        setBusy(true, "Téléchargement de " + item.getName() + "…");
        worker.execute(() -> {
            try {
                File file = Asset3DDownloader.download(
                        this,
                        item,
                        (downloaded, total) -> runOnUiThread(() -> {
                            String text = "Téléchargement " + item.getName()
                                    + " • " + Asset3DDownloader.formatBytes(downloaded);
                            if (total > 0L) {
                                text += " / " + Asset3DDownloader.formatBytes(total);
                            }
                            status.setText(text);
                        })
                );
                runOnUiThread(() -> {
                    setBusy(false, item.getName() + " téléchargé : "
                            + Asset3DDownloader.formatBytes(file.length())
                            + " • licence enregistrée dans le même dossier.");
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    openAsset(file, item);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    String detail = error.getMessage() == null
                            ? "erreur inconnue"
                            : error.getMessage();
                    setBusy(false, "Téléchargement impossible : " + detail);
                    Toast.makeText(
                            this,
                            "Asset non téléchargé : " + detail,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
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
                    + Asset3DDownloader.formatBytes(file.length()));
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
}

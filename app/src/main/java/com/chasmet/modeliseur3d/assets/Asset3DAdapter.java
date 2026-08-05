package com.chasmet.modeliseur3d.assets;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chasmet.modeliseur3d.R;

import java.util.List;

/** Affichage compact du catalogue sans bibliothèque supplémentaire. */
public final class Asset3DAdapter extends ArrayAdapter<Asset3DItem> {
    private final LayoutInflater inflater;
    private final ActionListener listener;

    public Asset3DAdapter(
            Context context,
            List<Asset3DItem> items,
            ActionListener listener
    ) {
        super(context, 0, items);
        inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    @NonNull
    @Override
    public View getView(
            int position,
            @Nullable View reusable,
            @NonNull ViewGroup parent
    ) {
        View row = reusable;
        Holder holder;
        if (row == null) {
            row = inflater.inflate(R.layout.row_asset_3d, parent, false);
            holder = new Holder(row);
            row.setTag(holder);
        } else {
            holder = (Holder) row.getTag();
        }

        Asset3DItem item = getItem(position);
        if (item == null) {
            return row;
        }
        holder.name.setText(item.getName());
        String origin = item.isGenerated() ? "LOCAL CC0" : "KHRONOS";
        holder.category.setText(item.getCategory()
                + " • " + origin
                + (item.isAnimated() ? " • ANIMÉ" : " • STATIQUE"));
        holder.description.setText(item.getDescription());
        holder.license.setText(item.getLicense() + " • " + item.getCredit());

        boolean ready = Asset3DDownloader.isDownloaded(getContext(), item);
        if (ready) {
            holder.action.setText("Ouvrir le GLB");
            holder.export.setText("Exporter le GLB");
        } else if (item.isGenerated()) {
            holder.action.setText("Créer le GLB hors ligne");
            holder.export.setText("Créer puis exporter");
        } else {
            holder.action.setText("Télécharger");
            holder.export.setText("Télécharger puis exporter");
        }
        holder.source.setText(item.isGenerated() ? "Licence" : "Source");
        holder.action.setOnClickListener(view -> listener.onAssetAction(item));
        holder.export.setOnClickListener(view -> listener.onExport(item));
        holder.source.setOnClickListener(view -> listener.onSource(item));
        return row;
    }

    public interface ActionListener {
        void onAssetAction(Asset3DItem item);

        void onExport(Asset3DItem item);

        void onSource(Asset3DItem item);
    }

    private static final class Holder {
        final TextView name;
        final TextView category;
        final TextView description;
        final TextView license;
        final Button action;
        final Button export;
        final Button source;

        Holder(View row) {
            name = row.findViewById(R.id.assetNameText);
            category = row.findViewById(R.id.assetCategoryText);
            description = row.findViewById(R.id.assetDescriptionText);
            license = row.findViewById(R.id.assetLicenseText);
            action = row.findViewById(R.id.assetDownloadButton);
            export = row.findViewById(R.id.assetExportButton);
            source = row.findViewById(R.id.assetSourceButton);
        }
    }
}

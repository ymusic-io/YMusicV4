package io.ymusic.files;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Browses shared storage and returns the chosen folder as a persistable, writable tree uri of [FilesProvider].
 * Answers only apps signed with this app's key: the grant is all-files access to that folder.
 */
public final class PickFolderActivity extends Activity {
    private static final String STATE_DIR = "dir";

    private final List<File> roots = new ArrayList<>();
    private final List<String> rootLabels = new ArrayList<>();
    /** Null on the volume list. */
    private File current;
    private final List<File> rowTargets = new ArrayList<>();
    private ArrayAdapter<String> rows;

    private View accessPane;
    private View browserPane;
    private TextView path;
    private ListView list;
    private Button useFolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String caller = getCallingPackage();
        if (caller == null || getPackageManager().checkSignatures(caller, getPackageName()) != PackageManager.SIGNATURE_MATCH) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        setTitle(R.string.pick_title);
        loadRoots();
        String saved = savedInstanceState != null ? savedInstanceState.getString(STATE_DIR) : null;
        current = saved != null ? new File(saved) : (roots.size() == 1 ? roots.get(0) : null);
        setContentView(buildViews());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The access toggle lives in Settings; coming back from it lands here.
        boolean granted = Environment.isExternalStorageManager();
        accessPane.setVisibility(granted ? View.GONE : View.VISIBLE);
        browserPane.setVisibility(granted ? View.VISIBLE : View.GONE);
        if (granted) show(current);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (current != null) outState.putString(STATE_DIR, current.getPath());
    }

    private void loadRoots() {
        StorageManager storage = getSystemService(StorageManager.class);
        for (StorageVolume volume : storage.getStorageVolumes()) {
            String state = volume.getState();
            File dir = volume.getDirectory();
            if (dir == null || !(Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))) continue;
            roots.add(dir);
            rootLabels.add(volume.getDescription(this));
        }
    }

    private View buildViews() {
        int gap = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(gap, gap, gap, gap);

        LinearLayout access = new LinearLayout(this);
        access.setOrientation(LinearLayout.VERTICAL);
        access.setGravity(Gravity.CENTER);
        TextView message = new TextView(this);
        message.setText(R.string.access_message);
        message.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium);
        message.setGravity(Gravity.CENTER);
        access.addView(message);
        Button grant = new Button(this);
        grant.setText(R.string.open_settings);
        grant.setOnClickListener(view -> openAccessSettings());
        LinearLayout.LayoutParams grantParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        grantParams.topMargin = gap;
        access.addView(grant, grantParams);
        screen.addView(access, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        accessPane = access;

        LinearLayout browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        path = new TextView(this);
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.START);
        path.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        browser.addView(path);
        list = new ListView(this);
        rows = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> show(rowTargets.get(position)));
        browser.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView empty = new TextView(this);
        empty.setText(R.string.empty);
        empty.setPadding(0, gap, 0, gap);
        browser.addView(empty);
        list.setEmptyView(empty);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        Button cancel = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        buttons.addView(cancel);
        useFolder = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        useFolder.setText(R.string.use_folder);
        useFolder.setOnClickListener(view -> pick());
        buttons.addView(useFolder);
        browser.addView(buttons);
        screen.addView(browser, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        browserPane = browser;
        return screen;
    }

    /** Lists [dir]'s folders, or the volumes for null. */
    private void show(File dir) {
        current = dir;
        rowTargets.clear();
        List<String> labels = new ArrayList<>();
        int root = rootIndexOf(dir);
        if (dir == null) {
            rowTargets.addAll(roots);
            labels.addAll(rootLabels);
            path.setText("");
        } else {
            File parent = roots.contains(dir) ? null : dir.getParentFile();
            if (parent != null || roots.size() > 1) {
                rowTargets.add(parent);
                labels.add("..");
            }
            File[] children = dir.listFiles(child -> child.isDirectory() && !child.isHidden());
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT)));
                for (File child : children) {
                    rowTargets.add(child);
                    labels.add(child.getName());
                }
            }
            String relative = root < 0 ? dir.getPath() : dir.getPath().substring(roots.get(root).getPath().length());
            path.setText(root < 0 ? relative : rootLabels.get(root) + relative);
        }
        rows.clear();
        rows.addAll(labels);
        list.setSelection(0);
        // The clicked row leaves with the listing it was in; on a D-pad focus must land on the new one.
        list.requestFocus();
        useFolder.setEnabled(dir != null);
    }

    private int rootIndexOf(File dir) {
        if (dir == null) return -1;
        for (int i = 0; i < roots.size(); i++) {
            String root = roots.get(i).getPath();
            if (dir.getPath().equals(root) || dir.getPath().startsWith(root + "/")) return i;
        }
        return -1;
    }

    private void pick() {
        if (current == null) return;
        Uri tree = DocumentsContract.buildTreeDocumentUri(FilesProvider.AUTHORITY, FilesProvider.documentId(current));
        setResult(RESULT_OK, new Intent().setData(tree).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        finish();
    }

    private void openAccessSettings() {
        Intent[] candidates = {
            new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent intent : candidates) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // next candidate
            }
        }
    }
}

package io.ymusic.files;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Shared storage as SAF documents; a document id is the file's absolute path, canonicalized on every use. */
public final class FilesProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.ymusic.files.documents";

    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    };
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS};

    @Override
    public boolean onCreate() {
        return true;
    }

    static String documentId(File file) {
        return file.getAbsolutePath();
    }

    private static File fileOf(String documentId) throws FileNotFoundException {
        File file = new File(documentId);
        if (!file.isAbsolute()) throw new FileNotFoundException("not a path: " + documentId);
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new FileNotFoundException(documentId + ": " + e.getMessage());
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        // Not listed in the system picker (no DOCUMENTS_PROVIDER filter), so it offers no roots.
        return new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        File file = fileOf(documentId);
        if (!file.exists()) throw new FileNotFoundException("missing: " + documentId);
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addRow(cursor, file, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = fileOf(parentDocumentId);
        File[] children = parent.listFiles();
        if (children == null) throw new FileNotFoundException("cannot list: " + parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        for (File child : children) addRow(cursor, child, documentId(child));
        return cursor;
    }

    private void addRow(MatrixCursor cursor, File file, String documentId) {
        boolean dir = file.isDirectory();
        boolean writable = file.canWrite();
        int flags = 0;
        if (dir && writable) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        if (!dir && writable) flags |= Document.FLAG_SUPPORTS_WRITE;
        if (file.getParentFile() != null && file.getParentFile().canWrite()) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, displayName(file))
            .add(Document.COLUMN_MIME_TYPE, dir ? Document.MIME_TYPE_DIR : mimeType(file.getName()))
            .add(Document.COLUMN_SIZE, dir ? null : file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags);
    }

    /** A volume root's own name is a mount id ("0", "1234-5678"); its label reads better. */
    private String displayName(File file) {
        StorageManager storage = getContext().getSystemService(StorageManager.class);
        for (StorageVolume volume : storage.getStorageVolumes()) {
            if (file.equals(volume.getDirectory())) return volume.getDescription(getContext());
        }
        return file.getName();
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type != null) return type;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String parent = fileOf(parentDocumentId).getPath();
            String child = fileOf(documentId).getPath();
            return child.equals(parent) || child.startsWith(parent.endsWith("/") ? parent : parent + "/");
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = fileOf(parentDocumentId);
        boolean dir = Document.MIME_TYPE_DIR.equals(mimeType);
        File file = freeFile(parent, displayName);
        try {
            boolean created = dir ? file.mkdir() : file.createNewFile();
            if (!created) throw new IllegalStateException("Failed to touch " + file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to touch " + file + ": " + e.getMessage(), e);
        }
        return documentId(file);
    }

    /** "name.ext", else "name (1).ext", "name (2).ext", … — the system storage provider's naming. */
    private static File freeFile(File parent, String displayName) {
        File file = new File(parent, displayName);
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : "";
        for (int n = 1; file.exists(); n++) file = new File(parent, base + " (" + n + ")" + ext);
        return file;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = fileOf(documentId);
        if (!file.exists()) throw new FileNotFoundException("missing: " + documentId);
        if (!deleteRecursively(file)) throw new IllegalStateException("Failed to delete " + file);
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete();
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = fileOf(documentId);
        File target = new File(file.getParentFile(), displayName);
        if (target.exists()) throw new IllegalStateException("Already exists " + target);
        if (!file.renameTo(target)) throw new IllegalStateException("Failed to rename " + file);
        return documentId(target);
    }
}

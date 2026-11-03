package za.co.switchgraphics.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Shares files from the app's cache (PDFs, camera photos) with WhatsApp, PDF viewers and the camera. */
public class FilesProvider extends ContentProvider {

    static final String AUTHORITY = "za.co.switchgraphics.hub.files";
    static File root;

    @Override
    public boolean onCreate() {
        if (getContext() != null) root = getContext().getCacheDir();
        return true;
    }

    static Uri uriFor(File f) {
        String rel = f.getParentFile().getName() + "/" + f.getName();
        return new Uri.Builder().scheme("content").authority(AUTHORITY).encodedPath("/" + Uri.encode(rel, "/")).build();
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    File fileFor(Uri uri) throws FileNotFoundException {
        if (root == null) throw new FileNotFoundException("not ready");
        try {
            File f = new File(root, uri.getPath()).getCanonicalFile();
            String base = root.getCanonicalPath() + File.separator;
            if (!f.getPath().startsWith(base)) throw new FileNotFoundException("outside cache");
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int m = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.parseMode(mode);
        if (m != ParcelFileDescriptor.MODE_READ_ONLY) {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
        }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public String getType(Uri uri) {
        return mimeOf(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}

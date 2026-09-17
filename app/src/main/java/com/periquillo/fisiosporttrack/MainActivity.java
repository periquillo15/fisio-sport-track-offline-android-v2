package com.periquillo.fisiosporttrack;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int BACKUP_EXPORT_REQUEST = 1002;
    private static final int BACKUP_IMPORT_REQUEST = 1003;
    private WebView webView;
    private NativeStore nativeStore;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;
    private String pendingBackupJson;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        nativeStore = new NativeStore(this);
        webView.addJavascriptInterface(nativeStore, "NativeStore");
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent galleryIntent = params.createIntent();
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraPhotoUri = createCameraImageUri();
                if (cameraPhotoUri != null) {
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                Intent intent = Intent.createChooser(galleryIntent, "Seleccionar fotografía");
                if (cameraPhotoUri != null) {
                    intent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception ex) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "(window.handleAndroidBack && window.handleAndroidBack()) ? 'true' : 'false'",
                value -> {
                    if (!"\"true\"".equals(value) && !"true".equals(value)) {
                        MainActivity.super.onBackPressed();
                    }
                }
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_EXPORT_REQUEST) {
            handleBackupExportResult(resultCode, data);
            return;
        }
        if (requestCode == BACKUP_IMPORT_REQUEST) {
            handleBackupImportResult(resultCode, data);
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) {
            return;
        }
        Uri[] uris = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                uris = new Uri[]{data.getData()};
            } else if (cameraPhotoUri != null) {
                uris = new Uri[]{cameraPhotoUri};
            }
        }
        filePathCallback.onReceiveValue(uris);
        filePathCallback = null;
        cameraPhotoUri = null;
    }

    private Uri createCameraImageUri() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "fisio-sport-track-" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void startBackupExport(String json) {
        pendingBackupJson = json;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "fisio-sport-track-backup.json");
        startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
    }

    private void startBackupImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, BACKUP_IMPORT_REQUEST);
    }

    private void handleBackupExportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pendingBackupJson == null) {
            pendingBackupJson = null;
            runJs("showToast('Exportacion cancelada')");
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new IllegalStateException("No se pudo abrir el archivo");
            output.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
            output.flush();
            runJs("setSyncStatus('synced','Copia exportada'); showToast('Copia exportada')");
        } catch (Exception ex) {
            runJs("setSyncStatus('error'," + JSONObject.quote(ex.getMessage()) + "); showToast(" + JSONObject.quote("Error exportando copia: " + ex.getMessage()) + ")");
        } finally {
            pendingBackupJson = null;
        }
    }

    private void handleBackupImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            runJs("showToast('Importacion cancelada')");
            return;
        }
        try (InputStream input = getContentResolver().openInputStream(data.getData())) {
            if (input == null) throw new IllegalStateException("No se pudo abrir el archivo");
            String json = readStream(input);
            nativeStore.importBackupJson(json);
            runJs("setSyncStatus('synced','Copia restaurada'); closeDialog('formDialog'); load(); showToast('Copia restaurada')");
        } catch (Exception ex) {
            runJs("setSyncStatus('error'," + JSONObject.quote(ex.getMessage()) + "); showToast(" + JSONObject.quote("Error restaurando copia: " + ex.getMessage()) + ")");
        }
    }

    private void runJs(String script) {
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private static String readStream(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static final class NativeStore {
        private final MainActivity activity;
        private final StoreDb db;

        NativeStore(MainActivity activity) {
            this.activity = activity;
            db = new StoreDb(activity.getApplicationContext());
        }

        @JavascriptInterface
        public String request(String payload) {
            try {
                JSONObject request = new JSONObject(payload);
                String collection = request.optString("db", "");
                String path = request.optString("path", "");
                JSONObject options = request.optJSONObject("options");
                String method = options != null ? options.optString("method", "GET") : "GET";
                String body = options != null ? options.optString("body", "") : "";

                if ("GET".equalsIgnoreCase(method) && path.isEmpty()) {
                    return db.list(collection).toString();
                }
                if ("GET".equalsIgnoreCase(method)) {
                    JSONObject item = db.get(collection, firstPathSegment(path));
                    return item != null ? item.toString() : "null";
                }
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject record = new JSONObject(body);
                    db.put(collection, record);
                    return record.toString();
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    db.delete(collection, firstPathSegment(path));
                    return new JSONObject().put("ok", true).toString();
                }
                return new JSONObject().put("error", "Operacion no soportada").toString();
            } catch (Exception ex) {
                try {
                    return new JSONObject().put("error", ex.getMessage()).toString();
                } catch (Exception ignored) {
                    return "{\"error\":\"Error nativo\"}";
                }
            }
        }

        private static String firstPathSegment(String path) {
            String normalized = path == null ? "" : path;
            while (normalized.startsWith("/")) normalized = normalized.substring(1);
            int slash = normalized.indexOf('/');
            return slash >= 0 ? normalized.substring(0, slash) : normalized;
        }

        @JavascriptInterface
        public String exportBackup() {
            try {
                String json = db.exportBackup().toString(2);
                activity.runOnUiThread(() -> activity.startBackupExport(json));
                return new JSONObject().put("ok", true).toString();
            } catch (Exception ex) {
                try {
                    return new JSONObject().put("error", ex.getMessage()).toString();
                } catch (Exception ignored) {
                    return "{\"error\":\"Error nativo\"}";
                }
            }
        }

        @JavascriptInterface
        public String importBackup() {
            try {
                activity.runOnUiThread(activity::startBackupImport);
                return new JSONObject().put("ok", true).toString();
            } catch (Exception ex) {
                try {
                    return new JSONObject().put("error", ex.getMessage()).toString();
                } catch (Exception ignored) {
                    return "{\"error\":\"Error nativo\"}";
                }
            }
        }

        void importBackupJson(String json) throws Exception {
            db.importBackup(new JSONObject(json));
        }
    }

    private static final class StoreDb extends SQLiteOpenHelper {
        private static final String DB_NAME = "fisio_sport_track.db";
        private static final int DB_VERSION = 4;
        private static final int APP_SCHEMA_VERSION = 4;
        private static final int DATA_CHUNK_SIZE = 180000;
        private static final String MEDIA_PREFIX = "fst-media://";
        private final Context context;

        StoreDb(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
            this.context = context;
            seedIfNeeded();
            migratePhotosOutOfRowsIfNeeded();
        }

        @Override
        public void onCreate(SQLiteDatabase database) {
            createBaseSchema(database);
            setMetadata(database, "schema_version", String.valueOf(APP_SCHEMA_VERSION));
            setMetadata(database, "created_at", String.valueOf(System.currentTimeMillis()));
            setMetadata(database, "updated_at", String.valueOf(System.currentTimeMillis()));
        }

        @Override
        public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            runMigrations(database, oldVersion, newVersion);
        }

        @Override
        public void onDowngrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            throw new IllegalStateException("La base de datos pertenece a una version mas reciente de FISIO SPORT TRACK");
        }

        private void createBaseSchema(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS records (collection TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(collection, id))");
            database.execSQL("CREATE INDEX IF NOT EXISTS records_collection_updated_idx ON records(collection, updated_at)");
            database.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
        }

        private void runMigrations(SQLiteDatabase database, int oldVersion, int newVersion) {
            database.beginTransaction();
            try {
                createBaseSchema(database);

                if (oldVersion < 2 && newVersion >= 2) {
                    migrateToVersion2(database);
                }
                if (oldVersion < 3 && newVersion >= 3) {
                    migrateToVersion3(database);
                }
                if (oldVersion < 4 && newVersion >= 4) {
                    migrateToVersion4(database);
                }

                setMetadata(database, "schema_version", String.valueOf(APP_SCHEMA_VERSION));
                setMetadata(database, "updated_at", String.valueOf(System.currentTimeMillis()));
                database.setTransactionSuccessful();
            } catch (Exception ex) {
                throw new IllegalStateException("No se pudo migrar la base de datos: " + ex.getMessage(), ex);
            } finally {
                database.endTransaction();
            }
        }

        private void migrateToVersion2(SQLiteDatabase database) {
            // Version 2 keeps the existing record store intact and adds durable
            // schema metadata so future APKs can migrate without reseeding.
            database.execSQL("CREATE INDEX IF NOT EXISTS records_collection_updated_idx ON records(collection, updated_at)");
            database.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
            setMetadata(database, "migration_2_applied_at", String.valueOf(System.currentTimeMillis()));
        }

        private void migrateToVersion3(SQLiteDatabase database) throws Exception {
            // Photos used to be embedded as base64 inside each JSON row. A single
            // large photo can exceed Android CursorWindow limits when SQLite reads
            // the row. Store photos as private app files and keep only a light
            // reference in SQLite.
            JSONArray rows = rowKeys(database);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String collection = row.optString("collection");
                String id = row.optString("id");
                JSONObject record = new JSONObject(readRecordData(database, collection, id));
                JSONObject stored = prepareForStorage(collection, record);
                ContentValues values = new ContentValues();
                values.put("data", stored.toString());
                values.put("updated_at", row.optLong("updatedAt", System.currentTimeMillis()));
                database.update("records", values, "collection = ? AND id = ?", new String[]{collection, id});
            }
            setMetadata(database, "migration_3_applied_at", String.valueOf(System.currentTimeMillis()));
        }

        private void migrateToVersion4(SQLiteDatabase database) throws Exception {
            migratePhotosOutOfRows(database);
            setMetadata(database, "migration_4_applied_at", String.valueOf(System.currentTimeMillis()));
        }

        private void migratePhotosOutOfRowsIfNeeded() {
            SQLiteDatabase database = getWritableDatabase();
            database.beginTransaction();
            try {
                createBaseSchema(database);
                int migrated = migratePhotosOutOfRows(database);
                if (migrated > 0) {
                    setMetadata(database, "photo_rows_compacted_at", String.valueOf(System.currentTimeMillis()));
                }
                setMetadata(database, "schema_version", String.valueOf(APP_SCHEMA_VERSION));
                database.setTransactionSuccessful();
            } catch (Exception ex) {
                throw new IllegalStateException("No se pudieron optimizar las fotografias existentes: " + ex.getMessage(), ex);
            } finally {
                database.endTransaction();
            }
        }

        private int migratePhotosOutOfRows(SQLiteDatabase database) throws Exception {
            int migrated = 0;
            JSONArray rows = rowKeys(database);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String collection = row.optString("collection");
                if (!"people".equals(collection) && !"groups".equals(collection)) continue;
                String id = row.optString("id");
                JSONObject record = new JSONObject(readRecordData(database, collection, id));
                String photo = record.optString("photoUrl", "");
                if (!photo.startsWith("data:image/") && !photo.startsWith("file://")) continue;
                JSONObject stored = prepareForStorage(collection, record);
                ContentValues values = new ContentValues();
                values.put("data", stored.toString());
                values.put("updated_at", row.optLong("updatedAt", System.currentTimeMillis()));
                database.update("records", values, "collection = ? AND id = ?", new String[]{collection, id});
                migrated++;
            }
            return migrated;
        }

        private static void setMetadata(SQLiteDatabase database, String key, String value) {
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", value);
            database.insertWithOnConflict("app_metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        JSONArray list(String collection) throws Exception {
            JSONArray items = new JSONArray();
            try (Cursor cursor = getReadableDatabase().query(
                    "records",
                    new String[]{"id"},
                    "collection = ?",
                    new String[]{collection},
                    null,
                    null,
                    "updated_at ASC"
            )) {
                while (cursor.moveToNext()) {
                    items.put(prepareForDisplay(collection, new JSONObject(readRecordData(collection, cursor.getString(0)))));
                }
            }
            return items;
        }

        JSONObject get(String collection, String id) throws Exception {
            try (Cursor cursor = getReadableDatabase().query(
                    "records",
                    new String[]{"id"},
                    "collection = ? AND id = ?",
                    new String[]{collection, id},
                    null,
                    null,
                    null
            )) {
                if (!cursor.moveToFirst()) return null;
                return prepareForDisplay(collection, new JSONObject(readRecordData(collection, id)));
            }
        }

        void put(String collection, JSONObject record) throws Exception {
            String id = record.optString("id", "");
            if (id.isEmpty()) throw new IllegalArgumentException("El registro no tiene id");
            JSONObject storedRecord = prepareForStorage(collection, record);
            ContentValues values = new ContentValues();
            values.put("collection", collection);
            values.put("id", id);
            values.put("data", storedRecord.toString());
            values.put("updated_at", System.currentTimeMillis());
            SQLiteDatabase database = getWritableDatabase();
            database.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            setMetadata(database, "updated_at", String.valueOf(System.currentTimeMillis()));
        }

        void delete(String collection, String id) {
            SQLiteDatabase database = getWritableDatabase();
            database.delete("records", "collection = ? AND id = ?", new String[]{collection, id});
            setMetadata(database, "updated_at", String.valueOf(System.currentTimeMillis()));
        }

        JSONObject exportBackup() throws Exception {
            JSONObject backup = new JSONObject();
            JSONArray records = new JSONArray();
            try (Cursor cursor = getReadableDatabase().query(
                    "records",
                    new String[]{"collection", "id", "updated_at"},
                    null,
                    null,
                    null,
                    null,
                    "collection ASC, updated_at ASC"
            )) {
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    row.put("collection", cursor.getString(0));
                    row.put("id", cursor.getString(1));
                    row.put("data", prepareForBackup(cursor.getString(0), new JSONObject(readRecordData(cursor.getString(0), cursor.getString(1)))));
                    row.put("updatedAt", cursor.getLong(2));
                    records.put(row);
                }
            }
            backup.put("app", "FISIO SPORT TRACK");
            backup.put("format", "sqlite-records");
            backup.put("version", 2);
            backup.put("schemaVersion", APP_SCHEMA_VERSION);
            backup.put("exportedAt", System.currentTimeMillis());
            backup.put("records", records);
            return backup;
        }

        void importBackup(JSONObject backup) throws Exception {
            if (!"FISIO SPORT TRACK".equals(backup.optString("app"))) {
                throw new IllegalArgumentException("La copia no pertenece a FISIO SPORT TRACK");
            }
            int backupSchemaVersion = backup.optInt("schemaVersion", 1);
            if (backupSchemaVersion > APP_SCHEMA_VERSION) {
                throw new IllegalArgumentException("La copia pertenece a una version mas reciente de la app");
            }
            JSONArray records = backup.optJSONArray("records");
            if (records == null) throw new IllegalArgumentException("Archivo de copia no valido");
            Set<String> allowedCollections = new HashSet<>(Arrays.asList("groups", "people", "injuries", "treatments"));
            for (int i = 0; i < records.length(); i++) {
                JSONObject row = records.getJSONObject(i);
                String collection = row.optString("collection");
                String id = row.optString("id");
                JSONObject data = row.optJSONObject("data");
                if (!allowedCollections.contains(collection) || id.isEmpty() || data == null) {
                    throw new IllegalArgumentException("La copia contiene un registro incompleto o no compatible");
                }
            }
            SQLiteDatabase database = getWritableDatabase();
            database.beginTransaction();
            try {
                database.delete("records", null, null);
                for (int i = 0; i < records.length(); i++) {
                    JSONObject row = records.getJSONObject(i);
                    String collection = row.optString("collection");
                    String id = row.optString("id");
                    JSONObject data = row.optJSONObject("data");
                    if (collection.isEmpty() || id.isEmpty() || data == null) {
                        throw new IllegalArgumentException("La copia contiene un registro incompleto");
                    }
                    ContentValues values = new ContentValues();
                    values.put("collection", collection);
                    values.put("id", id);
                    values.put("data", prepareForStorage(collection, data).toString());
                    values.put("updated_at", row.optLong("updatedAt", System.currentTimeMillis()));
                    database.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
                setMetadata(database, "schema_version", String.valueOf(APP_SCHEMA_VERSION));
                setMetadata(database, "restored_at", String.valueOf(System.currentTimeMillis()));
                setMetadata(database, "updated_at", String.valueOf(System.currentTimeMillis()));
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }

        private void seedIfNeeded() {
            SQLiteDatabase database = getWritableDatabase();
            try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM records", null)) {
                if (cursor.moveToFirst() && cursor.getInt(0) > 0) return;
            }
            try {
                JSONObject seed = new JSONObject(readAsset("seed.json"));
                seedCollection(database, "groups", seed.optJSONArray("groups"));
                seedCollection(database, "people", seed.optJSONArray("people"));
                seedCollection(database, "injuries", seed.optJSONArray("injuries"));
                seedCollection(database, "treatments", seed.optJSONArray("treatments"));
            } catch (Exception ignored) {
            }
        }

        private void seedCollection(SQLiteDatabase database, String collection, JSONArray items) throws Exception {
            if (items == null) return;
            database.beginTransaction();
            try {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject record = items.getJSONObject(i);
                    String id = record.optString("id");
                    if (id.isEmpty()) continue;
                    ContentValues values = new ContentValues();
                    values.put("collection", collection);
                    values.put("id", id);
                    values.put("data", prepareForStorage(collection, record).toString());
                    values.put("updated_at", System.currentTimeMillis());
                    database.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }

        private String readAsset(String name) throws Exception {
            try (InputStream input = context.getAssets().open(name);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }

        private JSONArray rowKeys(SQLiteDatabase database) throws Exception {
            JSONArray rows = new JSONArray();
            try (Cursor cursor = database.query(
                    "records",
                    new String[]{"collection", "id", "updated_at"},
                    null,
                    null,
                    null,
                    null,
                    "collection ASC, updated_at ASC"
            )) {
                while (cursor.moveToNext()) {
                    rows.put(new JSONObject()
                            .put("collection", cursor.getString(0))
                            .put("id", cursor.getString(1))
                            .put("updatedAt", cursor.getLong(2)));
                }
            }
            return rows;
        }

        private String readRecordData(String collection, String id) throws Exception {
            return readRecordData(getReadableDatabase(), collection, id);
        }

        private String readRecordData(SQLiteDatabase database, String collection, String id) throws Exception {
            int length = 0;
            try (Cursor cursor = database.rawQuery(
                    "SELECT length(data) FROM records WHERE collection = ? AND id = ?",
                    new String[]{collection, id}
            )) {
                if (!cursor.moveToFirst()) throw new IllegalArgumentException("Registro no encontrado");
                length = cursor.getInt(0);
            }
            StringBuilder data = new StringBuilder(length);
            for (int offset = 1; offset <= length; offset += DATA_CHUNK_SIZE) {
                try (Cursor cursor = database.rawQuery(
                        "SELECT substr(data, ?, ?) FROM records WHERE collection = ? AND id = ?",
                        new String[]{String.valueOf(offset), String.valueOf(DATA_CHUNK_SIZE), collection, id}
                )) {
                    if (cursor.moveToFirst()) data.append(cursor.getString(0));
                }
            }
            return data.toString();
        }

        private JSONObject prepareForStorage(String collection, JSONObject source) throws Exception {
            JSONObject record = new JSONObject(source.toString());
            if ("people".equals(collection) || "groups".equals(collection)) {
                String photo = record.optString("photoUrl", "");
                String stored = storePhotoReference(photo);
                if (!stored.equals(photo)) record.put("photoUrl", stored);
            }
            return record;
        }

        private JSONObject prepareForDisplay(String collection, JSONObject source) throws Exception {
            JSONObject record = new JSONObject(source.toString());
            if ("people".equals(collection) || "groups".equals(collection)) {
                String photo = record.optString("photoUrl", "");
                if (photo.startsWith(MEDIA_PREFIX)) {
                    record.put("photoUrl", mediaFileUri(photo));
                }
            }
            return record;
        }

        private JSONObject prepareForBackup(String collection, JSONObject source) throws Exception {
            JSONObject record = new JSONObject(source.toString());
            if ("people".equals(collection) || "groups".equals(collection)) {
                String photo = record.optString("photoUrl", "");
                if (photo.startsWith(MEDIA_PREFIX)) {
                    record.put("photoUrl", mediaRefToDataUrl(photo));
                } else if (photo.startsWith("file://")) {
                    record.put("photoUrl", fileUriToDataUrl(photo));
                }
            }
            return record;
        }

        private String storePhotoReference(String photo) throws Exception {
            if (photo == null || photo.isEmpty()) return "";
            if (photo.startsWith(MEDIA_PREFIX)) return photo;
            if (photo.startsWith("file://")) {
                File file = new File(Uri.parse(photo).getPath());
                if (isMediaFile(file)) return MEDIA_PREFIX + file.getName();
                return photo;
            }
            if (!photo.startsWith("data:image/")) return photo;

            int comma = photo.indexOf(',');
            int slash = photo.indexOf('/');
            int semicolon = photo.indexOf(';');
            if (comma < 0 || slash < 0 || semicolon < slash) return photo;
            String ext = "jpg";
            byte[] bytes = optimizedImageBytes(Base64.decode(photo.substring(comma + 1), Base64.DEFAULT));
            File file = new File(mediaDir(), UUID.randomUUID().toString() + "." + ext);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
            }
            return MEDIA_PREFIX + file.getName();
        }

        private byte[] optimizedImageBytes(byte[] original) {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(original, 0, original.length, bounds);
                int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
                if (maxSide <= 0) return original;
                BitmapFactory.Options options = new BitmapFactory.Options();
                int sample = 1;
                while (maxSide / sample > 1200) sample *= 2;
                options.inSampleSize = sample;
                Bitmap decoded = BitmapFactory.decodeByteArray(original, 0, original.length, options);
                if (decoded == null) return original;
                int width = decoded.getWidth();
                int height = decoded.getHeight();
                float scale = Math.min(1f, 900f / Math.max(width, height));
                Bitmap outputBitmap = decoded;
                if (scale < 1f) {
                    outputBitmap = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true);
                    decoded.recycle();
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
                outputBitmap.recycle();
                return output.toByteArray();
            } catch (Exception ignored) {
                return original;
            }
        }

        private String mediaFileUri(String ref) {
            File file = mediaFile(ref);
            return file.exists() ? "file://" + file.getAbsolutePath() : "";
        }

        private String mediaRefToDataUrl(String ref) throws Exception {
            File file = mediaFile(ref);
            if (!file.exists()) return "";
            return fileToDataUrl(file);
        }

        private String fileUriToDataUrl(String uri) throws Exception {
            File file = new File(Uri.parse(uri).getPath());
            if (!file.exists()) return "";
            return fileToDataUrl(file);
        }

        private String fileToDataUrl(File file) throws Exception {
            String ext = "";
            int dot = file.getName().lastIndexOf('.');
            if (dot >= 0) ext = file.getName().substring(dot + 1).toLowerCase();
            String mime = "png".equals(ext) ? "image/png" : "webp".equals(ext) ? "image/webp" : "gif".equals(ext) ? "image/gif" : "image/jpeg";
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                return "data:" + mime + ";base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            }
        }

        private File mediaFile(String ref) {
            String name = ref.startsWith(MEDIA_PREFIX) ? ref.substring(MEDIA_PREFIX.length()) : ref;
            return new File(mediaDir(), name);
        }

        private File mediaDir() {
            File dir = new File(context.getFilesDir(), "fst_media");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        }

        private boolean isMediaFile(File file) throws Exception {
            File dir = mediaDir().getCanonicalFile();
            File candidate = file.getCanonicalFile();
            return candidate.getParentFile() != null && candidate.getParentFile().equals(dir);
        }
    }
}

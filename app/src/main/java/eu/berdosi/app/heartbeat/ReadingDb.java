package eu.berdosi.app.heartbeat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The long-term log of readings. Every change is committed as it is made, so nothing is lost if
 * the app is killed. The file lives in the app's database directory, which Android's auto backup
 * includes.
 */
final class ReadingDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "readings.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "readings";

    /** Everything but the bulky raw dump. Index order matters for {@link #read}. */
    private static final String[] COLUMNS = {
            "_id", "timestamp", "bpm", "cycles", "seconds",
            "activity", "activity_auto", "duration_min", "duration_auto", "invalid",
            "gx", "gy", "gz"};

    ReadingDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "timestamp INTEGER NOT NULL, "
                + "bpm REAL NOT NULL, "
                + "cycles INTEGER NOT NULL, "
                + "seconds REAL NOT NULL, "
                + "details TEXT NOT NULL, "
                + "activity TEXT, "
                + "activity_auto INTEGER NOT NULL DEFAULT 0, "
                + "duration_min INTEGER, "
                + "duration_auto INTEGER NOT NULL DEFAULT 1, "
                + "invalid INTEGER NOT NULL DEFAULT 0, "
                + "gx REAL, gy REAL, gz REAL)");
        db.execSQL("CREATE INDEX readings_timestamp ON " + TABLE + " (timestamp)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first schema. Later versions must migrate with ALTER TABLE and
        // never drop the table: this is a long-term health record.
    }

    /** Stores the entry, sets its id. Throws SQLiteException when the write fails. */
    void insert(ReadingEntry entry, String details) {
        ContentValues values = new ContentValues();
        values.put("timestamp", entry.timestamp);
        values.put("bpm", entry.bpm);
        values.put("cycles", entry.cycles);
        values.put("seconds", entry.seconds);
        values.put("details", details);
        if (entry.gravity == null) {
            values.putNull("gx");
            values.putNull("gy");
            values.putNull("gz");
        } else {
            values.put("gx", entry.gravity[0]);
            values.put("gy", entry.gravity[1]);
            values.put("gz", entry.gravity[2]);
        }
        putEditable(values, entry);
        entry.id = getWritableDatabase().insertOrThrow(TABLE, null, values);
    }

    /** Writes the user-editable fields: activity, duration, invalid. */
    void update(ReadingEntry entry) {
        ContentValues values = new ContentValues();
        putEditable(values, entry);
        getWritableDatabase().update(TABLE, values, "_id = ?", new String[]{String.valueOf(entry.id)});
    }

    private static void putEditable(ContentValues values, ReadingEntry entry) {
        if (entry.activity == null) values.putNull("activity");
        else values.put("activity", entry.activity.name());
        values.put("activity_auto", entry.activityAuto ? 1 : 0);
        if (entry.durationMin == null) values.putNull("duration_min");
        else values.put("duration_min", entry.durationMin);
        values.put("duration_auto", entry.durationAuto ? 1 : 0);
        values.put("invalid", entry.invalid ? 1 : 0);
    }

    /** All entries, newest first. */
    List<ReadingEntry> list() {
        List<ReadingEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, COLUMNS, null, null, null, null, "timestamp DESC, _id DESC")) {
            while (cursor.moveToNext()) entries.add(read(cursor));
        }
        return entries;
    }

    /** The latest entry started before {@code timestamp}, or null. */
    ReadingEntry previous(long timestamp) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, COLUMNS, "timestamp < ?", new String[]{String.valueOf(timestamp)},
                null, null, "timestamp DESC, _id DESC", "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    /** The raw sample dump of one reading, as exported by "Export Details". */
    String details(long id) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE, new String[]{"details"}, "_id = ?", new String[]{String.valueOf(id)},
                null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private static ReadingEntry read(Cursor c) {
        float[] gravity = c.isNull(10)
                ? null
                : new float[]{c.getFloat(10), c.getFloat(11), c.getFloat(12)};
        ReadingEntry entry = new ReadingEntry(
                c.getLong(1), c.getFloat(2), c.getInt(3), c.getFloat(4), gravity);
        entry.id = c.getLong(0);
        entry.activity = ActivityLevel.fromName(c.getString(5));
        entry.activityAuto = c.getInt(6) != 0;
        entry.durationMin = c.isNull(7) ? null : c.getInt(7);
        entry.durationAuto = c.getInt(8) != 0;
        entry.invalid = c.getInt(9) != 0;
        return entry;
    }
}

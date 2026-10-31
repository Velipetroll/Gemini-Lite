package com.google.geminiclone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChatDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "gemini_chats.db";
    private static final int DATABASE_VERSION = 2;

    // Tables
    private static final String TABLE_CHATS = "chats";
    private static final String TABLE_MESSAGES = "messages";

    // Columns: chats
    private static final String COL_CHAT_ID = "id";
    private static final String COL_CHAT_TITLE = "title";
    private static final String COL_CHAT_CREATED = "created_at";
    private static final String COL_CHAT_UPDATED = "updated_at";

    // Columns: messages
    private static final String COL_MSG_ID = "id";
    private static final String COL_MSG_CHAT_ID = "chat_id";
    private static final String COL_MSG_TEXT = "text";
    private static final String COL_MSG_IS_USER = "is_user";
    private static final String COL_MSG_THINKING = "thinking_level";
    private static final String COL_MSG_MODEL = "model_name";
    private static final String COL_MSG_TIMESTAMP = "timestamp";
    private static final String COL_MSG_ATTACH_URI = "attachment_uri";
    private static final String COL_MSG_ATTACH_MIME = "attachment_mime";
    private static final String COL_MSG_ATTACH_NAME = "attachment_name";

    public ChatDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_CHATS + " ("
                + COL_CHAT_ID + " TEXT PRIMARY KEY, "
                + COL_CHAT_TITLE + " TEXT, "
                + COL_CHAT_CREATED + " INTEGER, "
                + COL_CHAT_UPDATED + " INTEGER);");

        db.execSQL("CREATE TABLE " + TABLE_MESSAGES + " ("
                + COL_MSG_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_MSG_CHAT_ID + " TEXT, "
                + COL_MSG_TEXT + " TEXT, "
                + COL_MSG_IS_USER + " INTEGER, "
                + COL_MSG_THINKING + " TEXT, "
                + COL_MSG_MODEL + " TEXT, "
                + COL_MSG_TIMESTAMP + " INTEGER, "
                + COL_MSG_ATTACH_URI + " TEXT, "
                + COL_MSG_ATTACH_MIME + " TEXT, "
                + COL_MSG_ATTACH_NAME + " TEXT, "
                + "FOREIGN KEY(" + COL_MSG_CHAT_ID + ") REFERENCES " + TABLE_CHATS + "(" + COL_CHAT_ID + ") ON DELETE CASCADE);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_MSG_ATTACH_URI + " TEXT;"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_MSG_ATTACH_MIME + " TEXT;"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_MSG_ATTACH_NAME + " TEXT;"); } catch (Exception ignored) {}
        }
    }

    public String createChat(String title) {
        String chatId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_ID, chatId);
        cv.put(COL_CHAT_TITLE, title);
        cv.put(COL_CHAT_CREATED, now);
        cv.put(COL_CHAT_UPDATED, now);

        db.insert(TABLE_CHATS, null, cv);
        return chatId;
    }

    public void updateChatTitle(String chatId, String newTitle) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_TITLE, newTitle);
        cv.put(COL_CHAT_UPDATED, System.currentTimeMillis());
        db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
    }

    public void touchChat(String chatId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_UPDATED, System.currentTimeMillis());
        db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
    }

    public void saveMessage(String chatId, ChatMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_CHAT_ID, chatId);
        cv.put(COL_MSG_TEXT, message.getText());
        cv.put(COL_MSG_IS_USER, message.isUser() ? 1 : 0);
        cv.put(COL_MSG_THINKING, message.getThinkingLevel());
        cv.put(COL_MSG_MODEL, message.getModelName());
        cv.put(COL_MSG_TIMESTAMP, message.getTimestamp());
        cv.put(COL_MSG_ATTACH_URI, message.getAttachmentUri());
        cv.put(COL_MSG_ATTACH_MIME, message.getAttachmentMimeType());
        cv.put(COL_MSG_ATTACH_NAME, message.getAttachmentName());

        db.insert(TABLE_MESSAGES, null, cv);
        touchChat(chatId);
    }

    public void updateLastGeminiMessage(String chatId, String completedText) {
        SQLiteDatabase db = getWritableDatabase();
        // Update the latest non-user message for this chat
        String sql = "UPDATE " + TABLE_MESSAGES + " SET " + COL_MSG_TEXT + "=? "
                + "WHERE " + COL_MSG_ID + " = ("
                + "  SELECT " + COL_MSG_ID + " FROM " + TABLE_MESSAGES
                + "  WHERE " + COL_MSG_CHAT_ID + "=? AND " + COL_MSG_IS_USER + "=0 "
                + "  ORDER BY " + COL_MSG_ID + " DESC LIMIT 1"
                + ")";
        db.execSQL(sql, new Object[]{completedText, chatId});
        touchChat(chatId);
    }

    public List<ChatSession> getAllChats() {
        List<ChatSession> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_CHATS, null, null, null, null, null, COL_CHAT_UPDATED + " DESC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_TITLE));
                long created = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CHAT_CREATED));
                long updated = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CHAT_UPDATED));
                list.add(new ChatSession(id, title, created, updated));
            }
            cursor.close();
        }
        return list;
    }

    public List<ChatMessage> getMessagesForChat(String chatId) {
        List<ChatMessage> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_MESSAGES, null, COL_MSG_CHAT_ID + "=?", new String[]{chatId}, null, null, COL_MSG_ID + " ASC");

        if (cursor != null) {
            int idxAttachUri = cursor.getColumnIndex(COL_MSG_ATTACH_URI);
            int idxAttachMime = cursor.getColumnIndex(COL_MSG_ATTACH_MIME);
            int idxAttachName = cursor.getColumnIndex(COL_MSG_ATTACH_NAME);
            while (cursor.moveToNext()) {
                String text = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_TEXT));
                boolean isUser = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MSG_IS_USER)) == 1;
                String thinking = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_THINKING));
                String model = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_MODEL));
                ChatMessage msg = new ChatMessage(text, isUser, false, thinking, model);
                if (idxAttachUri >= 0) {
                    String attachUri = cursor.getString(idxAttachUri);
                    if (attachUri != null && !attachUri.isEmpty()) {
                        msg.setAttachmentUri(attachUri);
                        if (idxAttachMime >= 0) msg.setAttachmentMimeType(cursor.getString(idxAttachMime));
                        if (idxAttachName >= 0) msg.setAttachmentName(cursor.getString(idxAttachName));
                    }
                }
                list.add(msg);
            }
            cursor.close();
        }
        return list;
    }

    public void deleteChat(String chatId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, COL_MSG_CHAT_ID + "=?", new String[]{chatId});
        db.delete(TABLE_CHATS, COL_CHAT_ID + "=?", new String[]{chatId});
    }

    public void clearAllChats() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, null, null);
        db.delete(TABLE_CHATS, null, null);
    }
}

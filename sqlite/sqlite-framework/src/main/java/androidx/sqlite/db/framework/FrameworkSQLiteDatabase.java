/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.sqlite.db.framework;

import static android.text.TextUtils.isEmpty;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteTransactionListener;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Pair;

import androidx.annotation.DoNotInline;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteCompat;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteStatement;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Delegates all calls to an implementation of {@link SQLiteDatabase}.
 */
@SuppressWarnings("unused")
class FrameworkSQLiteDatabase implements SupportSQLiteDatabase {
    private static final String[] CONFLICT_VALUES = new String[]
            {"", " OR ROLLBACK ", " OR ABORT ", " OR FAIL ", " OR IGNORE ", " OR REPLACE "};
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private final SQLiteDatabase mDelegate;

    /**
     * Creates a wrapper around {@link SQLiteDatabase}.
     *
     * @param delegate The delegate to receive all calls.
     */
    FrameworkSQLiteDatabase(SQLiteDatabase delegate) {
        mDelegate = delegate;
    }

    @NonNull
    @Override
    public SupportSQLiteStatement compileStatement(@NonNull String sql) {
        return new FrameworkSQLiteStatement(mDelegate.compileStatement(sql));
    }

    @Override
    public void beginTransaction() {
        mDelegate.beginTransaction();
    }

    @Override
    public void beginTransactionNonExclusive() {
        mDelegate.beginTransactionNonExclusive();
    }

    @Override
    public void beginTransactionWithListener(
            @NonNull SQLiteTransactionListener transactionListener) {
        mDelegate.beginTransactionWithListener(transactionListener);
    }

    @Override
    public void beginTransactionWithListenerNonExclusive(
            @NonNull SQLiteTransactionListener transactionListener) {
        mDelegate.beginTransactionWithListenerNonExclusive(transactionListener);
    }

    @Override
    public void endTransaction() {
        mDelegate.endTransaction();
    }

    @Override
    public void setTransactionSuccessful() {
        mDelegate.setTransactionSuccessful();
    }

    @Override
    public boolean inTransaction() {
        return mDelegate.inTransaction();
    }

    @Override
    public boolean isDbLockedByCurrentThread() {
        return mDelegate.isDbLockedByCurrentThread();
    }

    @Override
    public boolean yieldIfContendedSafely() {
        return mDelegate.yieldIfContendedSafely();
    }

    @Override
    public boolean yieldIfContendedSafely(long sleepAfterYieldDelay) {
        return mDelegate.yieldIfContendedSafely(sleepAfterYieldDelay);
    }

    @Override
    public boolean isExecPerConnectionSQLSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    @Override
    public void execPerConnectionSQL(@NonNull String sql, @Nullable Object[] bindArgs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Impl.execPerConnectionSQL(mDelegate, sql, bindArgs);
        } else {
            throw new UnsupportedOperationException("execPerConnectionSQL is not supported on a "
                    + "SDK version lower than 30, current version is: " + Build.VERSION.SDK_INT);
        }
    }

    @Override
    public int getVersion() {
        return mDelegate.getVersion();
    }

    @Override
    public void setVersion(int version) {
        mDelegate.setVersion(version);
    }

    @Override
    public long getMaximumSize() {
        return mDelegate.getMaximumSize();
    }

    @Override
    public long setMaximumSize(long numBytes) {
        return mDelegate.setMaximumSize(numBytes);
    }

    @Override
    public long getPageSize() {
        return mDelegate.getPageSize();
    }

    @Override
    public void setPageSize(long numBytes) {
        mDelegate.setPageSize(numBytes);
    }

    @Override
    public @NonNull Cursor query(@NonNull String query) {
        return query(new SimpleSQLiteQuery(query));
    }

    @Override
    public @NonNull Cursor query(@NonNull String query, @NonNull Object[] bindArgs) {
        return query(new SimpleSQLiteQuery(query, bindArgs));
    }

    @Override
    public @NonNull Cursor query(@NonNull final SupportSQLiteQuery supportQuery) {
        return mDelegate.rawQueryWithFactory((db, masterQuery, editTable, query) -> {
            supportQuery.bindTo(new FrameworkSQLiteProgram(query));
            return new SQLiteCursor(masterQuery, editTable, query);
        }, supportQuery.getSql(), EMPTY_STRING_ARRAY, null);
    }

    @Override
    @RequiresApi(16)
    public @NonNull Cursor query(@NonNull final SupportSQLiteQuery supportQuery,
            CancellationSignal cancellationSignal) {
        return SupportSQLiteCompat.Api16Impl.rawQueryWithFactory(mDelegate, supportQuery.getSql(),
                EMPTY_STRING_ARRAY, null, cancellationSignal,
                (db, masterQuery, editTable, query) -> {
                    supportQuery.bindTo(new FrameworkSQLiteProgram(query));
                    return new SQLiteCursor(masterQuery, editTable, query);
                });
    }

    @Override
    public long insert(@NonNull String table, int conflictAlgorithm, @NonNull ContentValues values)
            throws SQLException {
        return mDelegate.insertWithOnConflict(table, null, values,
                conflictAlgorithm);
    }

    @Override
    public int delete(@NonNull String table, String whereClause, Object[] whereArgs) {
        String query = "DELETE FROM " + table
                + (isEmpty(whereClause) ? "" : " WHERE " + whereClause);
        SupportSQLiteStatement statement = compileStatement(query);
        SimpleSQLiteQuery.bind(statement, whereArgs);
        return statement.executeUpdateDelete();
    }


    @Override
    public int update(@NonNull String table, int conflictAlgorithm, ContentValues values,
            String whereClause, Object[] whereArgs) {
        // taken from SQLiteDatabase class.
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Empty values");
        }
        StringBuilder sql = new StringBuilder(120);
        sql.append("UPDATE ");
        sql.append(CONFLICT_VALUES[conflictAlgorithm]);
        sql.append(table);
        sql.append(" SET ");

        // move all bind args to one array
        int setValuesSize = values.size();
        int bindArgsSize = (whereArgs == null) ? setValuesSize : (setValuesSize + whereArgs.length);
        Object[] bindArgs = new Object[bindArgsSize];
        int i = 0;
        for (String colName : values.keySet()) {
            sql.append((i > 0) ? "," : "");
            sql.append(colName);
            bindArgs[i++] = values.get(colName);
            sql.append("=?");
        }
        if (whereArgs != null) {
            for (i = setValuesSize; i < bindArgsSize; i++) {
                bindArgs[i] = whereArgs[i - setValuesSize];
            }
        }
        if (!isEmpty(whereClause)) {
            sql.append(" WHERE ");
            sql.append(whereClause);
        }
        SupportSQLiteStatement stmt = compileStatement(sql.toString());
        SimpleSQLiteQuery.bind(stmt, bindArgs);
        return stmt.executeUpdateDelete();
    }

    @Override
    public void execSQL(@NonNull String sql) throws SQLException {
        mDelegate.execSQL(sql);
    }

    @Override
    public void execSQL(@NonNull String sql, Object[] bindArgs) throws SQLException {
        mDelegate.execSQL(sql, bindArgs);
    }

    @Override
    public boolean isReadOnly() {
        return mDelegate.isReadOnly();
    }

    @Override
    public boolean isOpen() {
        return mDelegate.isOpen();
    }

    @Override
    public boolean needUpgrade(int newVersion) {
        return mDelegate.needUpgrade(newVersion);
    }

    @Override
    public String getPath() {
        return mDelegate.getPath();
    }

    @Override
    public void setLocale(@NonNull Locale locale) {
        mDelegate.setLocale(locale);
    }

    @Override
    public void setMaxSqlCacheSize(int cacheSize) {
        mDelegate.setMaxSqlCacheSize(cacheSize);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    public void setForeignKeyConstraintsEnabled(boolean enable) {
        SupportSQLiteCompat.Api16Impl.setForeignKeyConstraintsEnabled(mDelegate, enable);
    }

    @Override
    public boolean enableWriteAheadLogging() {
        return mDelegate.enableWriteAheadLogging();
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    public void disableWriteAheadLogging() {
        SupportSQLiteCompat.Api16Impl.disableWriteAheadLogging(mDelegate);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    public boolean isWriteAheadLoggingEnabled() {
        return SupportSQLiteCompat.Api16Impl.isWriteAheadLoggingEnabled(mDelegate);
    }

    @Override
    public List<Pair<String, String>> getAttachedDbs() {
        return mDelegate.getAttachedDbs();
    }

    @Override
    public boolean isDatabaseIntegrityOk() {
        return mDelegate.isDatabaseIntegrityOk();
    }

    @Override
    public void close() throws IOException {
        mDelegate.close();
    }

    /**
     * Checks if this object delegates to the same given database reference.
     */
    boolean isDelegate(SQLiteDatabase sqLiteDatabase) {
        return mDelegate == sqLiteDatabase;
    }

    @RequiresApi(30)
    static class Api30Impl {
        private Api30Impl() {
            // This class is not instantiable.
        }

        @DoNotInline
        static void execPerConnectionSQL(SQLiteDatabase sQLiteDatabase, String sql,
                Object[] bindArgs) {
            sQLiteDatabase.execPerConnectionSQL(sql, bindArgs);
        }
    }
}

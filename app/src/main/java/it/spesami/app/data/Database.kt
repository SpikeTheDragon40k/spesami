package it.spesami.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SpesamiDatabase(context: Context) : SQLiteOpenHelper(context, "spesami.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE ingredient (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                category TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE ingredient_alias (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE,
                alias TEXT NOT NULL,
                alias_key TEXT NOT NULL UNIQUE)"""
        )
        db.execSQL(
            """CREATE TABLE recipe (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE recipe_ingredient (
                recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
                ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                PRIMARY KEY (recipe_id, ingredient_id))"""
        )
        db.execSQL(
            """CREATE TABLE meal_slot (
                week_start TEXT NOT NULL,
                day INTEGER NOT NULL,
                meal TEXT NOT NULL,
                recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
                PRIMARY KEY (week_start, day, meal))"""
        )
        db.execSQL("CREATE TABLE preference (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

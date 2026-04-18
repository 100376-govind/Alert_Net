package com.alertnet.app.db

import android.database.sqlite.SQLiteDatabase
import com.alertnet.app.model.MeshPeer

object PeerQueries {
    fun insertPeer(db: SQLiteDatabase, peer: MeshPeer) {
        val cv = peer.toContentValues()
        db.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getActivePeers(db: SQLiteDatabase, since: Long): List<MeshPeer> {
        val peers = mutableListOf<MeshPeer>()
        db.rawQuery("SELECT * FROM peers WHERE lastSeen >= ? ORDER BY lastSeen DESC", arrayOf(since.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                peers.add(cursor.toMeshPeer())
            }
        }
        return peers
    }

    fun deleteStale(db: SQLiteDatabase, before: Long) {
        db.delete("peers", "lastSeen < ?", arrayOf(before.toString()))
    }
}

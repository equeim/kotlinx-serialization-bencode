package org.equeim.bencode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.test.Test

@Serializable
data class TorrentFile(
    val announce: String? = null,
    @SerialName("announce-list")
    val announceList: List<List<String>> = emptyList(),
    val info: Info
) {
    @Serializable
    data class Info(
        val files: List<File>? = null,
        val length: Long? = null,
        val name: String
    )

    @Serializable
    data class File(val length: Long, val path: List<String>)
}

class BencodeTest {
    @Test
    fun `Parsing torrent file`() {
        val torrentFile: TorrentFile = File("")
            .inputStream().buffered().use(Bencode::decode)
        println(torrentFile)
    }
}

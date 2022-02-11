package xyz.vonxxghost.script.util

import java.io.File
import java.io.FileInputStream
import java.lang.IllegalArgumentException
import java.util.zip.Adler32
import java.util.zip.Checksum


/**
 * Created by Vonn.Li on 2022/2/11 14:29
 */
object FileUtils {

    /**
     * Adler32算法计算文件CRC
     *
     * @return CRC值
     */
    fun File.crc(): Long {
        if (!isFile) {
            throw IllegalArgumentException("Only file can calculate CRC")
        }
        val buffer = ByteArray(1024)
        val checksum: Checksum = Adler32()
        val input = FileInputStream(this)
        var len: Int
        while (input.read(buffer).also { len = it } > 0) {
            checksum.update(buffer, 0, len)
        }
        return checksum.value
    }
}


package xyz.vonxxghost.ssync.util

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.IllegalArgumentException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

@Suppress("ControlFlowWithEmptyBody")
fun File.copyRecursively(
    target: File,
    overwrite: Boolean = false,
    copyAttributes: Boolean = false,
    onError: (File, IOException) -> OnErrorAction = { _, exception -> throw exception }
): Boolean {
    if (!exists()) {
        return onError(this, NoSuchFileException(file = this, reason = "The source file doesn't exist.")) !=
                OnErrorAction.TERMINATE
    }
    try {
        // We cannot break for loop from inside a lambda, so we have to use an exception here
        for (src in walkTopDown().onFail { f, e -> if (onError(f, e) == OnErrorAction.TERMINATE) throw FileSystemException(f) }) {
            if (!src.exists()) {
                if (onError(src, NoSuchFileException(file = src, reason = "The source file doesn't exist.")) ==
                    OnErrorAction.TERMINATE)
                    return false
            } else {
                val relPath = src.toRelativeString(this)
                val dstFile = File(target, relPath)
                if (dstFile.exists() && !(src.isDirectory && dstFile.isDirectory)) {
                    val stillExists = if (!overwrite) true else {
                        if (dstFile.isDirectory)
                            !dstFile.deleteRecursively()
                        else
                            !dstFile.delete()
                    }

                    if (stillExists) {
                        if (onError(dstFile, FileAlreadyExistsException(file = src,
                                other = dstFile,
                                reason = "The destination file already exists.")) == OnErrorAction.TERMINATE)
                            return false

                        continue
                    }
                }

                if (src.isDirectory) {
                    dstFile.mkdirs()
                } else {
                    // 这里改了
                    if (copyAttributes) {
                        Files.copy(src.toPath(), dstFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                    } else {
                        if (src.copyTo(dstFile, overwrite).length() != src.length()) {
                            if (onError(src, IOException("Source file wasn't copied completely, length of destination file differs.")) == OnErrorAction.TERMINATE){}
                            return false
                        }
                    }
                }
            }
        }
        return true
    } catch (e: FileSystemException) {
        return false
    }
}

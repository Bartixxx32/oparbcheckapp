package com.bartixxx.oneplusarbchecker.utils

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ArbExtractor {
    private const val TAG = "ARB_CHECKER"
    private const val ELF_MAGIC = 0x7F454C46
    private const val EI_CLASS = 4
    private const val ELFCLASS64 = 2

    fun extractArbFromImage(file: File): Int? {
        if (!file.exists()) {
            Log.e(TAG, "extractArbFromImage: File does not exist: ${file.absolutePath}")
            return null
        }
        Log.d(TAG, "extractArbFromImage: Processing ${file.name} (size: ${file.length()})")

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val ehdr = ByteArray(64)
                raf.readFully(ehdr)

                if (ehdr[0].toInt() != 0x7F || ehdr[1].toInt() != 'E'.code || 
                    ehdr[2].toInt() != 'L'.code || ehdr[3].toInt() != 'F'.code) {
                    Log.e(TAG, "extractArbFromImage: Invalid ELF magic")
                    return null
                }

                if (ehdr[EI_CLASS].toInt() != ELFCLASS64) {
                    Log.e(TAG, "extractArbFromImage: Not ELF64")
                    return null
                }

                val buffer = ByteBuffer.wrap(ehdr).order(ByteOrder.LITTLE_ENDIAN)
                val e_phoff = buffer.getLong(0x20)
                val e_phentsz = buffer.getShort(0x36).toInt() and 0xFFFF
                val e_phnum = buffer.getShort(0x38).toInt() and 0xFFFF
                Log.d(TAG, "ELF64 Header: phoff=$e_phoff, phentsz=$e_phentsz, phnum=$e_phnum")

                var hashOff = 0L
                var hashSize = 0L

                // Look for HASH segment (type 0 on some loaders, or just find largest data)
                for (i in e_phnum - 1 downTo 0) {
                    raf.seek(e_phoff + i.toLong() * e_phentsz)
                    val phdrBytes = ByteArray(e_phentsz)
                    raf.readFully(phdrBytes)
                    val phdr = ByteBuffer.wrap(phdrBytes).order(ByteOrder.LITTLE_ENDIAN)
                    
                    val p_type = phdr.getInt(0)
                    val p_offset = phdr.getLong(8)
                    val p_filesz = phdr.getLong(32)

                    if (p_type == 0 && p_filesz > 0L) {
                        hashOff = p_offset
                        hashSize = p_filesz
                        Log.d(TAG, "Found HASH segment at $hashOff (size: $hashSize)")
                        break
                    }
                }

                if (hashSize == 0L) {
                    Log.e(TAG, "extractArbFromImage: HASH segment not found")
                    return null
                }

                raf.seek(hashOff)
                val seg = ByteArray(hashSize.toInt())
                raf.readFully(seg)

                var headerOff = -1
                var commonSz = 0
                var qtiSz = 0

                val segBuffer = ByteBuffer.wrap(seg).order(ByteOrder.LITTLE_ENDIAN)

                for (off in 0 until (hashSize.toInt() - 36).coerceAtMost(0x2000) step 4) {
                    val version = segBuffer.getInt(off)
                    val common_sz = segBuffer.getInt(off + 4)
                    val qti_sz = segBuffer.getInt(off + 8)
                    val oem_sz = segBuffer.getInt(off + 12)
                    val hash_tbl_sz = segBuffer.getInt(off + 16)

                    if (version < 1 || version > 10) continue
                    if (common_sz > 0x1000 || oem_sz > 0x4000 || hash_tbl_sz > 0x4000) continue
                    if (off + 36 + common_sz + qti_sz + oem_sz > hashSize) continue

                    headerOff = off
                    commonSz = common_sz
                    qtiSz = qti_sz
                    Log.d(TAG, "Found Metadata Header at offset $off (commonSz=$commonSz, qtiSz=$qtiSz)")
                    break
                }

                if (headerOff == -1) {
                    Log.e(TAG, "extractArbFromImage: Metadata Header not found")
                    return null
                }

                val oemMdOff = headerOff + 36 + commonSz + qtiSz
                val arbIndex = segBuffer.getInt(oemMdOff + 8)
                Log.d(TAG, "Extracted ARB Index: $arbIndex")
                arbIndex
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ARB", e)
            null
        }
    }
}

package com.bartixxx.oneplusarbchecker

import com.bartixxx.oneplusarbchecker.utils.ArbExtractor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArbExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `test ARB extraction from dummy ELF`() {
        val file = tempFolder.newFile("xbl_config.img")
        val content = ByteArray(8192)
        val buffer = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN)

        // ELF Header
        buffer.put(0x7F.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('L'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put(4, 2.toByte()) // ELFCLASS64

        val phoff = 1024L
        buffer.putLong(0x20, phoff)
        buffer.putShort(0x36, 56.toShort()) // e_phentsz
        buffer.putShort(0x38, 1.toShort())  // e_phnum

        // Program Header (at phoff)
        val p_offset = 2048L
        val p_filesz = 1024L
        buffer.putInt(phoff.toInt() + 0, 0) // p_type = PT_NULL
        buffer.putLong(phoff.toInt() + 8, p_offset)
        buffer.putLong(phoff.toInt() + 32, p_filesz)

        // HASH Segment (at p_offset)
        val headerOff = p_offset.toInt() + 128
        buffer.putInt(headerOff + 0, 1)    // version
        buffer.putInt(headerOff + 4, 64)   // common_sz
        buffer.putInt(headerOff + 8, 32)   // qti_sz
        buffer.putInt(headerOff + 12, 32)  // oem_sz
        buffer.putInt(headerOff + 16, 128) // hash_tbl_sz

        // OEM Metadata (at headerOff + 36 + common_sz + qti_sz = headerOff + 36 + 64 + 32 = headerOff + 132)
        val oemMdOff = headerOff + 36 + 64 + 32
        buffer.putInt(oemMdOff + 0, 2) // Major
        buffer.putInt(oemMdOff + 4, 1) // Minor
        buffer.putInt(oemMdOff + 8, 4) // ARB VALUE

        file.writeBytes(content)

        val result = ArbExtractor.extractArbFromImage(file)
        if (result == null) {
             throw RuntimeException("Extraction failed - result was null")
        }
        assertEquals(4, result)
    }
}

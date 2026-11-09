package com.deangi.androidpdp11.storage

import android.system.Os
import java.io.FileDescriptor

/** Reads and writes a fixed-size, seekable document descriptor without truncation. */
internal class DescriptorImageAccess(private val descriptor: FileDescriptor, private val size: Int) : ImageAccess {
    override fun read(): ByteArray {
        check(Os.fstat(descriptor).st_size == size.toLong()) { "Image size changed or provider is not seekable" }
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = Os.pread(descriptor, result, offset, size - offset, offset.toLong())
            check(n > 0) { "Could not read complete image" }
            offset += n
        }
        return result
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size == size) { "Image length cannot change" }
        check(Os.fstat(descriptor).st_size == size.toLong()) { "Image size changed" }
        var offset = 0
        while (offset < size) {
            val n = Os.pwrite(descriptor, bytes, offset, size - offset, offset.toLong())
            check(n > 0) { "Could not write complete image" }
            offset += n
        }
    }

    override fun sync() = Os.fsync(descriptor)
}

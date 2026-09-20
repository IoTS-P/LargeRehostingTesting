package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.Address
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.MemoryAccessException
import java.util.*

/**
 * 增强型内存搜索器。
 * 提供高效的内存搜索功能，支持对齐检查和跳过零值区域。
 *
 * @param program Ghidra 程序对象。
 */
class EnhancedMemorySearcher(val program: Program) {
    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    fun nextAddressNotZero(addr: Address, align: Int): Address? {
        return nextAddressNotZero(addr, program.maxAddress, align)
    }

    /**
     * 查找下一个非零地址。
     * 从指定地址开始搜索，跳过连续的零值字节，返回第一个非零值的地址。
     *
     * @param addr 起始搜索地址。
     * @param ceil 搜索上限地址。
     * @param align 对齐大小（1、2、4 或 8 字节）。
     * @return 下一个非零地址，如果不存在则返回 null。
     * @throws MemoryAccessException 如果内存访问失败。
     * @throws IllegalArgumentException 如果对齐参数无效。
     */
    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    fun nextAddressNotZero(addr: Address, ceil: Address, align: Int): Address? {
        var addr = addr
        if (!program.memory.contains(addr))
            return null

        if (Arrays.stream(SUPPORTED_ALIGNS).noneMatch { a: Int -> a == align })
            throw IllegalArgumentException("Invalid align: $align")

        // 处理包含大量零字节的超大二进制文件，需要跳过这些空的大间隙
        while (addr.offset % align != 0L) {
            val i = program.memory.getByte(addr)
            if (i != 0.toByte()) return addr
            addr = addr.add(1)
        }

        while (addr < ceil.subtract(ceil.offset % align) && ceil.subtract(addr) >= 8) {
            val i = program.memory.getLong(addr)
            if (i != 0L) break
            addr = addr.add(8)
        }

        if (ceil.subtract(addr) < align)
            return null

        var bias = 0
        while ((bias < 8 / align) && (addr < ceil)) {
            var v: Long = 0
            when (align) {
                1 -> v = program.memory.getByte(addr.add((bias * align).toLong())).toLong()
                2 -> v = program.memory.getShort(addr.add((bias * align).toLong())).toLong()
                4 -> v = program.memory.getInt(addr.add((bias * align).toLong())).toLong()
                8 -> v = program.memory.getLong(addr.add((bias * align).toLong()))
            }
            if (v != 0L) return addr.add((bias * align).toLong())
            bias++
        }

        return addr
    }


    /**
     * 查找下一个不等于指定值的地址。
     * 搜索内存中第一个不等于给定避免值数组的地址。
     *
     * @param addr 起始搜索地址。
     * @param avoids 要避免的值数组。
     * @return 第一个不包含在避免列表中的地址。
     * @throws MemoryAccessException 如果内存访问失败。
     * @throws IllegalArgumentException 如果参数无效。
     */
    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    inline fun <reified T : Comparable<T>> nextAddressNot(addr: Address, avoids: Array<T>): Address {
        return nextAddressNot(addr, program.maxAddress.add(1), avoids)
    }


    /**
     * 查找下一个不等于指定值的地址（带上限）。
     * 在指定范围内搜索第一个不等于给定避免值数组的地址。
     *
     * @param addr 起始搜索地址。
     * @param ceil 搜索上限地址。
     * @param avoids 要避免的值数组。
     * @return 第一个不包含在避免列表中的地址，如果不存在则返回上限地址。
     * @throws MemoryAccessException 如果内存访问失败。
     * @throws IllegalArgumentException 如果参数无效。
     */
    @Throws(MemoryAccessException::class, IllegalArgumentException::class)
    inline fun <reified T : Comparable<T>> nextAddressNot(addr: Address, ceil: Address, avoids: Array<T>): Address {
        if (addr > ceil) return ceil

        var ceil = ceil
        var ptr = addr

        val t = TreeSet(listOf(*avoids))

        val align = MemoryUtil.intClassToSize(T::class)

        if ((ceil.offset - addr.offset) % align != 0L) ceil = ceil.subtract((ceil.offset - addr.offset) % align)

        while (ptr < ceil) {
            val v = MemoryUtil.readSmall(program, ptr, align)

            if (t.stream().noneMatch { tt: T -> (tt as Number).toLong() == v }) return ptr

            ptr = ptr.add(align.toLong())
        }

        return ceil
    }

    companion object {
        /**
         * 支持的对齐大小数组。
         */
        val SUPPORTED_ALIGNS: IntArray = intArrayOf(1, 2, 4, 8)
    }
}

package org.iotsplab.akiba.utils.memory

import ghidra.features.base.memsearch.searcher.MemoryMatch
import ghidra.program.model.address.Address
import ghidra.program.model.address.AddressSpace
import ghidra.program.model.listing.Program
import ghidra.program.model.mem.Memory
import ghidra.program.model.mem.MemoryAccessException
import ghidra.util.task.TaskMonitor
import org.iotsplab.akiba.module.Log
import java.nio.charset.Charset
import kotlin.reflect.KClass

/**
 * 内存工具类。
 * 提供内存访问、转换和操作的各种实用方法。
 */
class MemoryUtil {

    companion object {
        /**
         * 页掩码常量，用于页面对齐计算。
         */
        const val PAGE_MASK = 0xFFFF_FFFF_FFFF_F000UL

        /**
         * 读取小尺寸的内存值。
         * 根据指定的大小（1、2、4、8 字节）从内存中读取整数值。
         *
         * @param program Ghidra 程序对象。
         * @param address 要读取的内存地址。
         * @param size 读取的字节数（1、2、4 或 8）。
         * @return 读取到的长整型值。
         * @throws IllegalArgumentException 如果大小参数不支持。
         * @throws MemoryAccessException 如果内存访问失败。
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, MemoryAccessException::class)
        fun readSmall(program: Program, address: Address, size: Int) : Long {
            return when (size) {
                1 -> program.memory.getByte(address).toLong()
                2 -> program.memory.getShort(address).toLong()
                4 -> program.memory.getInt(address).toLong()
                8 -> program.memory.getInt(address).toLong()
                else -> throw IllegalArgumentException("Unsupported alignment: $size")
            }
        }

        /**
         * 将整数类型类转换为对应的字节大小。
         *
         * @param type Kotlin 整数类型类（Byte、Short、Int、Long）。
         * @return 类型对应的字节大小（1、2、4 或 8）。
         * @throws UnsupportedOperationException 如果类型不是整数类型。
         */
        @JvmStatic
        @Throws(UnsupportedOperationException::class)
        fun<T : Any> intClassToSize(type: KClass<T>) : Int {
            return when {
                type.isInstance(0.toByte()) -> 1
                type.isInstance(0.toShort()) -> 2
                type.isInstance(0.toInt()) -> 4
                type.isInstance(0.toLong()) -> 8
                else -> throw UnsupportedOperationException("${(type as Any).javaClass.simpleName} is not integer type")
            }
        }

        /**
         * 将字节数组转换为长整型。
         * 按小端序将字节数组转换为 Long 值。
         *
         * @param bytes 要转换的字节数组（最多 8 字节）。
         * @return 转换后的长整型值。
         * @throws IllegalArgumentException 如果字节数组超过 8 字节。
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun bytesToLong(bytes: ByteArray): Long {
            require(bytes.size <= 8) { "Byte array too long (${bytes.size})" }
            var ret: Long = 0
            bytes.forEachIndexed { index, byte -> ret = ret or (byte.toLong() shl (index * 8)) }
            return ret
        }

        /**
         * 将数字转换为字节数组。
         * 根据数字的实际类型（Byte、Short、Int、Long）转换为对应的字节数组。
         *
         * @param value 要转换的数字值。
         * @return 转换后的字节数组。
         * @throws UnsupportedOperationException 如果类型不支持。
         */
        @JvmStatic
        @Throws(UnsupportedOperationException::class)
        fun<T> numberToBytes(value: T): ByteArray {
            return when (value) {
                is Byte -> byteArrayOf(value)
                is Short -> byteArrayOf(value.toByte(), (value.toInt() shr 8).toByte())
                is Int -> numberToBytes((value and 0xffff).toShort())
                    .plus(numberToBytes((value shr 16).toShort()))
                is Long -> numberToBytes((value and 0xffff_ffff).toInt())
                    .plus(numberToBytes((value shr 32).toInt()))
                else -> throw IllegalArgumentException("Unsupported type: ${value?.javaClass?.simpleName?:"null"}")
            }
        }

        /**
         * 从内存中读取字节并转换为 UTF-8 字符串。
         *
         * @param memory Ghidra 内存对象。
         * @param addr 起始地址。
         * @param length 要读取的字节数。
         * @return 转换后的 UTF-8 字符串。
         * @throws MemoryAccessException 如果内存访问失败。
         */
        @JvmStatic
        @Throws(MemoryAccessException::class)
        fun readProgramBytesToUTF8String(memory: Memory, addr: Address, length: Int): String {
            val matchedBytes = ByteArray(length)
            memory.getBytes(addr, matchedBytes)
            return matchedBytes.toString(Charset.forName("UTF-8"))
        }

        /**
         * 从内存匹配结果中读取字节并转换为 UTF-8 字符串。
         *
         * @param memory Ghidra 内存对象。
         * @param result 内存匹配结果对象。
         * @return 转换后的 UTF-8 字符串。
         * @throws MemoryAccessException 如果内存访问失败。
         */
        @JvmStatic
        @Throws(MemoryAccessException::class)
        fun readProgramBytesToUTF8String(memory: Memory, result: MemoryMatch): String {
            return readProgramBytesToUTF8String(memory, result.address, result.length)
        }

        /**
         * 移动整个内存块到新的基地址。
         * 将最小地址的内存块移动到指定的镜像基地址。
         *
         * @param memory Ghidra 内存对象。
         * @param imageBase 目标镜像基地址。
         * @throws MemoryAccessException 如果内存访问失败。
         */
        @JvmStatic
        @Throws(MemoryAccessException::class)
        fun moveWholeBlock(memory: Memory, imageBase: Long) {
            val addressSpace = memory.program.addressFactory.defaultAddressSpace
            val block = memory.getBlock(memory.minAddress)
            memory.moveBlock(block, addressSpace.getAddress(imageBase), TaskMonitor.DUMMY)
        }

        /**
         * 按偏移量移动所有内存块。
         * 将所有内存块按照指定的偏移量进行相对移动，保持块之间的相对位置不变。
         *
         * @param memory Ghidra 内存对象。
         * @param offset 移动的偏移量（正数向前移，负数向后移）。
         * @throws MemoryAccessException 如果内存访问失败。
         * @throws IllegalArgumentException 如果移动会导致地址下溢或溢出。
         */
        @JvmStatic
        @Throws(MemoryAccessException::class, IllegalArgumentException::class)
        fun moveAllBlocksInOffset(memory: Memory, offset: Long) {
            Log.current.info("Moving to relative address ${offset.toString(16)}")
            val blocks = memory.blocks.sortedBy { it.start }
            if (offset < 0 && blocks.first().start.offset < -offset)
                throw IllegalArgumentException("Movement of ${offset.toString(16)} will cause addr downflow")
            else if (offset > 0) {
                val memorySpaceCeil = memory.blocks.last().addressRange.addressSpace.maxAddress.offset
                try {
                    if (Math.addExact(memory.blocks.last().end.offset, offset) > memorySpaceCeil)
                        throw IllegalArgumentException(
                            "Movement of ${offset.toString(16)} will cause addr space overflow")
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("Movement of ${offset.toString(16)} will cause addr overflow")
                }
            } else if (offset == 0L)  // offset == 0，无需移动
                return
            // 为避免移动过程中重叠，如果 offset > 0，应该从最后一个块开始
            // 如果 offset < 0，应该从第一个块开始
            if (offset > 0)
                blocks.reversed().forEach { block ->
                    memory.moveBlock(block, block.start.add(offset), TaskMonitor.DUMMY)
                }
            else
                blocks.forEach { block ->
                    memory.moveBlock(block, block.start.subtract(-offset), TaskMonitor.DUMMY)
                }
        }

        /**
         * 获取地址的页起始地址（不检查有效性）。
         * 返回地址所在的 4KB 页面的起始地址。
         *
         * @return 页面对齐的地址。
         */
        @JvmStatic
        fun Address.getPageStartUnchecked(): Address {
            return subtract(offset % 0x1000)
        }

        /**
         * 获取包含指定地址范围的页面。
         *
         * @param addr 起始地址。
         * @param length 地址范围长度。
         * @return 包含起始和结束页面的对。
         */
        @JvmStatic
        fun getPagesIncluding(addr: Address, length: Long): Pair<Address, Address> {
            val pageStart = addr.getPageStartUnchecked()
            val pageEnd = addr.add(length - 1).getPageStartUnchecked()
            return Pair(pageStart, pageEnd)
        }

        /**
         * 根据 ID 获取地址空间。
         *
         * @param id 地址空间 ID。
         * @param program Ghidra 程序对象。
         * @return 对应的地址空间对象。
         */
        @JvmStatic
        fun getAddressSpace(id: Int, program: Program): AddressSpace {
            return program.addressFactory.getAddressSpace(id)
        }

        /**
         * 检查地址是否无效。
         * 判断地址是否在低端或高端的保留区域（0 页或最高页）。
         *
         * @param addr 要检查的地址。
         * @return 如果地址无效则返回 true，否则返回 false。
         */
        @JvmStatic
        fun addrIsInvalid(addr: Address): Boolean {
            return addr.offset in 0L..0xfff ||
                    addr.offset in
                    (1.toBigInteger() shl (addr.size * 8) - 0x1000).toLong()..
                    (1.toBigInteger() shl (addr.size * 8) - 1).toLong()
        }

        /**
         * 查找内存中给定大小的最高可用空闲空间。
         * 可用于为不关心栈的模拟生成可能可用的栈空间。
         *
         * @param size 需要的空闲空间大小。
         * @return 找到的空闲空间起始地址，如果不存在则返回 null。
         */
        @JvmStatic
        fun Memory.getUpmostFreeSpace(size: Long): Address? {
            var start = this.maxAddress.subtract(size - 1)
            blocks.sortedBy { it.start }.reversed().forEach {
                if (it.contains(start))
                    start = it.start.subtract(size)
                else
                    return start
            }
            return null
        }
    }
}

/**
 * 尝试将字符串解析为长整型。
 * 支持多种进制格式：二进制（0b 前缀）、八进制（0 前缀）、十进制、十六进制（0x 前缀）。
 *
 * @param str 要解析的字符串。
 * @return 解析后的长整型值，如果无法解析则返回 null。
 */
fun tryParseToLong(str: String): Long? {
    listOf(2, 8, 10, 16).forEach { radix ->
        when (radix) {
            2 -> if (str.startsWith("0b") || str.startsWith("-0b"))
                str.substring(2).toLongOrNull(radix) ?. let { return it }
            8 -> if (str.startsWith("0") || str.startsWith("-0"))
                str.substring(1).toLongOrNull(radix) ?. let { return it }
            10 -> str.substring(1).toLongOrNull(radix) ?. let { return it }
            16 -> if (str.startsWith("0x") || str.startsWith("-0x"))
                str.substring(2).toLongOrNull(radix) ?. let { return it }
        }
    }
    return null
}
package org.iotsplab.akiba.utils.memory

import ghidra.program.model.address.Address

/**
 * 内存段数据类。
 * 表示一个内存映射区域，包含文件偏移、大小、权限等信息。
 *
 * @param fileOffset 在文件中的偏移量（可选）。
 * @param size 内存段大小。
 * @param mapStart 映射到的起始地址。
 * @param description 内存段描述。
 * @param name 内存段名称。
 * @param prot 保护标志（读、写、执行的组合）。
 */
data class MemorySection(
    var fileOffset: Long?,
    var size: Long,
    var mapStart: Address,
    var description: String = "",
    var name: String = "",
    var prot: Int = PROT_WRITE or PROT_READ or PROT_EXEC,
) {
    override fun toString(): String {
        return """
            File offset: (${ fileOffset ?. let { "${it.toString(16)}~${(it + size).toString(16)}" } 
                                        ?: run { "Not mapped to any file section" }})
            Size: 0x${size.toString(16)}
            Map to: ${mapStart.addressSpace.name}:${mapStart.offset.toString(16)}~${(mapStart.offset + size).toString(16)}
            name: $name
            Description: $description
        """.trimIndent()
    }

    companion object {
        /**
         * 将内存段列表转换为格式化的字符串表示。
         *
         * @param sections 内存段列表。
         * @return 格式化后的内存段信息字符串。
         */
        fun sectionListString(sections: List<MemorySection>): String {
            return if (sections.isEmpty()) "Empty" else sections.sortedBy { it.mapStart } .mapIndexed { idx, s ->
                "Section #$idx:\n$s"
            }.joinToString("\n\n")
        }

        /**
         * 写保护标志。
         */
        const val PROT_WRITE = 0x1
        
        /**
         * 读保护标志。
         */
        const val PROT_READ = 0x2
        
        /**
         * 执行保护标志。
         */
        const val PROT_EXEC = 0x4
    }
}
package org.iotsplab.akiba.utils.assembly

/**
 * ARM Cortex-M 指令常量定义。
 * 包含不同操作数大小的指令助记符集合。
 */
object ArmcmInstConsts {
    /**
     * 操作数大小为 1 字节的指令助记符集合。
     * 包括 LDRB、LDRSB、STRB 等字节操作指令。
     */
    val OPERAND_SIZE_1_MNEMONICS: Set<String> = setOf("LDRB", "LDRSB", "STRB", "LDRBT", "LDRSBT", "STRBT")
    
    /**
     * 操作数大小为 2 字节的指令助记符集合。
     * 包括 LDRH、LDRSH、STRH 等半字操作指令。
     */
    val OPERAND_SIZE_2_MNEMONICS: Set<String> = setOf("LDRH", "LDRSH", "STRH", "LDRST", "LDRSHT", "STRHT")
}
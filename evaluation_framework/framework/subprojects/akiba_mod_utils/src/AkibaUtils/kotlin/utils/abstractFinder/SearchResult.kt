package org.iotsplab.akiba.utils.abstractFinder

/**
 * 搜索结果的抽象基类。
 * 用于封装搜索结果，提供统一的比较和字符串表示方法。
 *
 * @param T 搜索结果值的数据类型。
 * @property value 搜索结果的实际值。
 */
abstract class SearchResult<T>(val value: T) {
    /**
     * 检查两个搜索结果是否相等。
     * 基于值的内容进行比较。
     *
     * @param other 要比较的另一个对象。
     * @return 如果两个搜索结果的值相等则返回 true，否则返回 false。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return value == (other as SearchResult<*>).value
    }

    /**
     * 返回搜索结果的哈希码。
     * 基于值的哈希码计算。
     *
     * @return 此搜索结果的哈希码值。
     */
    override fun hashCode(): Int {
        return value.hashCode()
    }
    
    /**
     * 返回搜索结果的字符串表示。
     * 格式为：类名：值。
     *
     * @return 描述此搜索结果的字符串。
     */
    override fun toString(): String {
        return "${javaClass.simpleName}: ${value.toString()}"
    }
}
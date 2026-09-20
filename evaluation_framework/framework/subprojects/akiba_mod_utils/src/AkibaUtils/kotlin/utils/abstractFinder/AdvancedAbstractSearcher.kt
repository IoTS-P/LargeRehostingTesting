package org.iotsplab.akiba.utils.abstractFinder

/**
 * 高级抽象搜索器的基类。
 * 用于定义通用的搜索接口，支持各种类型的搜索结果。
 *
 * @param T 搜索结果的数据类型。
 */
abstract class AdvancedAbstractSearcher<T> {
    /**
     * 执行搜索操作并返回结果列表。
     * 具体的搜索逻辑由子类实现。
     *
     * @return 搜索结果的列表，包含所有匹配的项。
     * @throws Exception 如果搜索过程中发生错误。
     */
    @Throws(Exception::class)
    abstract fun search(): List<T>
}
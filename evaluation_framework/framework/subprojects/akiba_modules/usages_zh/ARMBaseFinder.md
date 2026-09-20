# ARM Cortex 固件基地址获取模块 —— ARMBaseFinder

## 分类

- 物联网固件

## 描述

尝试确定一个 ARM Cortex 系列固件的基地址、入口地址、中断向量表地址。

## 数据与模块交互

### 临时数据

- 🅞 `ArmcmIVT ivt`: 找到的中断向量表结构。

### 配置文件

配置文件类：`org.iotsplab.akiba.process.ARMBaseFinderConfig`

```json
{
  "totalMatchThreshold": 10,        // 最少匹配数量阈值，即差值的最少出现次数
  "totalMatchRatioThreshold": 0.7,  // 最少匹配比例阈值，即差值所占比例的最小值
  "uniqueMatchThreshold": 10,       // 最少去重匹配数量阈值，即差值对应的原地址值去重后的数量
  "scoreThreshold": 5,              // 最小分数阈值，即匹配的分数阈值
  "compulsoryBaseAlignment": 256,   // 基地址对齐要求
  "compulsoryIVTAlignment": 256,    // 中断向量表对齐要求
  
  "checkTopMatchesCount": 5,        // 动态验证的最大候选基地址数量
  "tryDifferentAlignment": false,   // 尝试不同的对齐方式（向文件开头添加1、2、3个字节以调整对齐）
  "matchThreadNumber": 1,           // 匹配线程数
  "compulsoryCheckForEntry": false  // 是否验证入口地址
}
```

### 模块依赖

- 🔴 `org.iotsplab.akiba.process.IoTGeneralStructures`: 物联网固件相关数据结构定义。
- 🔴 `org.iotsplab.akiba.process.ProgramServer`: 提前构建需要的数据映射，用于加速匹配流程。

### 数据表列

- 🟢 `base_address INTEGER`: 获取的基地址值。
- 🟢 `entry_point INTEGER`: 获取的入口地址值。
- 🟢 `ivt_start INTEGER`: 中断向量表的首地址。

## 补充解释

基地址匹配算法的原理是**函数地址匹配**。ARM Cortex 系列有一个特殊的结构——**中断向量表（`IVT`）**，其中包含多个函数地址。因此我们的匹配策略可以总结为以下几步：

1. 找到所有的候选`IVT`（`IVT`具有一些特征，本模块设计了一些启发式模式匹配算法用于识别这些特征），并提取其中的所有函数指针，为集合 $F_i$。
2. 在 Ghidra 自动分析完成后提取固件中的所有函数 $F$。
3. 对于 $\forall f_i \in F_i$，$\forall f \in F$，计算候选基地址 $f_i - f$。获取所有的差值，集合为可重复集合 $S$。
4. 获取 $S$ 中出现次数最多的值，并通过几轮筛选（静态判断+动态验证），最终获得最有可能的数个基地址候选值。
5. 使用一个打分机制对最终候选值进行筛选，取分数最大者，获取`IVT`、基地址、入口地址。

## 备注

❗ **性能警告**: 当固件内函数数量较多时，本模块可能会消耗大量内存与时间。
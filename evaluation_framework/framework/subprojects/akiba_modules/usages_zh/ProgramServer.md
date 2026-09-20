# Ghidra API 速度优化模块 —— ProgramServer

## 分类

- 物联网固件

## 描述

在函数不被修改时，优化两个函数 API 的性能。

## 数据与模块交互

### 提供的 API

- 🟠 `fun getFunctionContaining(address: Long): Function?`: 获取一个包含给定的地址的函数，比 Ghidra API 更快。
- 🟠 `fun getFunctionStart(function: Function): Long`: 获取一个给定函数的首地址，比 Ghidra API 更快。

## 补充解释

通过构建一个函数地址的 hashmap，我们可以更快地获取一个包含给定地址的函数，以及获取一个给定函数的起始地址，因为 Ghidra 需要动态地寻找函数。
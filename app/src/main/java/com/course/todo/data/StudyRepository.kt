package com.course.todo.data

import com.course.todo.core.StudySnapshot

fun interface StudyRepository {
    suspend fun fetch(): StudySnapshot
}

// 兼容旧引用：异常定义已移动到 core 包，data 包保留类型别名。
typealias AuthenticationRequired = com.course.todo.core.AuthenticationRequired
typealias UpstreamFailure = com.course.todo.core.UpstreamFailure

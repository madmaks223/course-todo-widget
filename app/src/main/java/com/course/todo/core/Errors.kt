package com.course.todo.core

/** 课程平台会话已失效，需要重新登录。 */
class AuthenticationRequired : Exception("登录已失效，请重新登录课程平台")

/** 课程平台要求人工完成访问验证时抛出（同步层会暂停自动同步并提示）。 */
class RiskControlRequired : Exception("课程平台要求完成访问验证，请打开网页完成验证后重试")

/** 上游接口异常（网络错误、非课程平台跳转、非 2xx 响应等）。 */
class UpstreamFailure(message: String) : Exception(message)

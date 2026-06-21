package com.popoto.core.message

enum class ProtocolCommand(val wireValue: String) {
    DISCOVER_HYDROPHONE("discover_hydrophone"),
    DISCOVER_REPLY("discover_reply"),
    GET_VERSION("get_version"),
    GET_VERSION_REPLY("get_version_reply"),
    SET_IP("set_ip"),
    SET_IP_REPLY("set_ip_reply"),
    SET_RTC("set_rtc"),
    SET_RTC_REPLY("set_rtc_reply"),
    GET_RTC("get_rtc"),
    GET_RTC_REPLY("get_rtc_reply"),
    SET_PARAM("set_param"),
    SET_PARAM_REPLY("set_param_reply"),
    SET_UBOOT_ENV("set_uboot_env"),
    SET_UBOOT_ENV_REPLY("set_uboot_env_reply"),
    REBOOT("reboot"),
    REBOOT_REPLY("reboot_reply"),
    SHELL_EXEC("shell_exec"),
    SHELL_EXEC_REPLY("shell_exec_reply"),
}

package com.xuan25.heartratestreamer

enum class HeartRateStatus {
    Init,
    CheckingCapabilities,
    SensorSupported,
    SensorNotSupported,
    RequestingPermissions,
    PermissionsGranted,
    Starting,
    WaitingForData,
    Streaming,
    Stopping,
    Stopped,
    PermissionDenied,
    Error
}

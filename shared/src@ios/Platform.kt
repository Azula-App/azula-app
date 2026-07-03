package dev.azula

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun platformName() = "ios"

actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

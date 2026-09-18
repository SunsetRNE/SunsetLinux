package android.system

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 测试替身：内核只用到 `Os.chmod`（给 `control.sock` 收紧成 0600）。
 * 真机上它要求**静态**方法，所以这里也必须 `@JvmStatic`（否则反射调用会以
 * `NoSuchMethodException` 失败 —— 这正是本替身要守住的东西之一）。
 */
object Os {
    @JvmStatic
    fun chmod(path: String, mode: Int) {
        val perms = if (mode and 0x1FF == 0x180) "rw-------" else "rw-rw-rw-"
        Files.setPosixFilePermissions(File(path).toPath(), PosixFilePermissions.fromString(perms))
    }
}

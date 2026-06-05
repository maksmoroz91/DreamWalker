package com.example.vpn_app

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor

class Tun2SocksRunner(private val context: Context) {
    companion object {
        private const val TAG = "Tun2SocksRunner"
        private const val BINARY_NAME = "libtun2socks.so"
    }

    private var process: Process? = null
    private var loggerThread: Thread? = null

    fun start(
        tunFd: ParcelFileDescriptor,
        socksHost: String,
        socksPort: Int,
        mtu: Int,
    ): Boolean {
        if (process != null) stop()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "tun2socks fd inheritance requires Android 8.0/API 26 or newer")
            return false
        }

        val binaryFile = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binaryFile.exists()) {
            Log.e(TAG, "tun2socks binary not found at: ${binaryFile.absolutePath}")
            logNativeLibraryDir(binaryFile.parentFile)
            return false
        }

        val command = listOf(
            binaryFile.absolutePath,
            "--device", "fd://${OsConstants.STDIN_FILENO}",
            "--proxy", "socks5://$socksHost:$socksPort",
            "--loglevel", "info",
            "--mtu", mtu.toString(),
        )
        Log.i(TAG, "Starting tun2socks: ${command.joinToString(" ")}")

        return synchronized(this) {
            var tunForChild: FileDescriptor? = null
            var stdinBackup: FileDescriptor? = null
            var stdinReplaced = false
            try {
                tunForChild = Os.dup(tunFd.fileDescriptor)
                stdinBackup = dupStdinOrNull()

                Os.dup2(tunForChild, OsConstants.STDIN_FILENO)
                stdinReplaced = true

                val builder = ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectErrorStream(true)
                val startedProcess = builder.start()
                process = startedProcess
                startLogger(startedProcess)

                Log.i(TAG, "tun2socks started successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tun2socks", e)
                process?.destroy()
                process = null
                loggerThread = null
                false
            } finally {
                restoreStdin(stdinBackup, stdinReplaced)
                closeQuietly(tunForChild)
            }
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy tun2socks", e)
        }
        process = null
        loggerThread = null
        Log.i(TAG, "tun2socks stopped")
    }

    private fun startLogger(startedProcess: Process) {
        loggerThread = Thread {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.i("tun2socks", line) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Logger thread error: ${e.message}")
            }
        }.apply {
            name = "tun2socks-logger"
            isDaemon = true
            start()
        }
    }

    private fun dupStdinOrNull(): FileDescriptor? {
        return try {
            Os.dup(FileDescriptor.`in`)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EBADF) {
                throw e
            }
            null
        }
    }

    private fun restoreStdin(stdinBackup: FileDescriptor?, stdinReplaced: Boolean) {
        try {
            if (!stdinReplaced) {
                return
            } else if (stdinBackup != null) {
                Os.dup2(stdinBackup, OsConstants.STDIN_FILENO)
            } else {
                Os.close(FileDescriptor.`in`)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore stdin after starting tun2socks", e)
        } finally {
            closeQuietly(stdinBackup)
        }
    }

    private fun closeQuietly(fd: FileDescriptor?) {
        if (fd == null) return

        try {
            Os.close(fd)
        } catch (e: Exception) {
            Log.w(TAG, "close fd", e)
        }
    }

    private fun logNativeLibraryDir(directory: File?) {
        Log.e(TAG, "nativeLibraryDir: ${directory?.absolutePath ?: "<null>"}")
        val files = directory?.listFiles()?.joinToString { it.name }.orEmpty()
        Log.e(TAG, "Files in nativeLibraryDir: $files")
    }
}
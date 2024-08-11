package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.gsi.IGsiService
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.ImagePartition
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

class DSUInstaller(
    private val application: Application,
    private val userdataSize: Long,
    private val onInstallationError: (error: InstallationStep, errorInfo: String) -> Unit,
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onCreatePartition: (partition: String) -> Unit,
    private val onInstallationStepUpdate: (step: InstallationStep) -> Unit,
    private val onInstallationSuccess: () -> Unit
) : DynamicSystemImpl() {

    private val tag = this.javaClass.simpleName
    private val installationJob = Job()

    object Constants {
        const val DEFAULT_SLOT = "dsu"
        const val SHARED_MEM_SIZE: Int = 524288
        const val MIN_PROGRESS_TO_PUBLISH = (1 shl 27).toLong()
    }

    private class MappedMemoryBuffer(var mBuffer: ByteBuffer?) : AutoCloseable {
        override fun close() {
            if (mBuffer != null) {
                SharedMemory.unmap(mBuffer!!)
                mBuffer = null
            }
        }
    }

    private val UNSUPPORTED_PARTITIONS: List<String> = listOf(
        "vbmeta",
        "boot",
        "userdata",
        "dtbo",
        "super_empty",
        "system_other",
        "scratch"
    )

    private fun isPartitionSupported(partitionName: String): Boolean =
        !UNSUPPORTED_PARTITIONS.contains(partitionName)

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(
            sharedMemory.javaClass,
            sharedMemory,
            "getFdDup"
        ) as ParcelFileDescriptor
    }

    private fun shouldInstallEntry(name: String): Boolean {
        return name.endsWith(".img") && isPartitionSupported(name.substringAfterLast("."))
    }

    private fun publishProgress(bytesRead: Long, totalBytes: Long, partition: String) {
        val progress = if (totalBytes != 0L) {
            (bytesRead.toFloat() / totalBytes.toFloat())
        } else 0F
        onInstallationProgressUpdate(progress, partition)
    }

    private fun installImage(
        partition: String,
        uncompressedSize: Long,
        inputStream: InputStream,
        readOnly: Boolean = true
    ) {
        val sis = SparseInputStream(BufferedInputStream(inputStream))
        val partitionSize = sis.unsparseSize.takeIf { it != -1L } ?: uncompressedSize

        onCreatePartition(partition)
        createNewPartition(partition, partitionSize, readOnly)
        onInstallationStepUpdate(InstallationStep.INSTALLING_ROOTED)

        SharedMemory.create("dsu_buffer_$partition", Constants.SHARED_MEM_SIZE).use { sharedMemory ->
            MappedMemoryBuffer(sharedMemory.mapReadWrite()).use { mappedBuffer ->
                val fdDup = getFdDup(sharedMemory)
                setAshmem(fdDup, sharedMemory.size.toLong())
                publishProgress(0L, partitionSize, partition)

                val readBuffer = ByteArray(sharedMemory.size)
                val buffer = mappedBuffer.mBuffer
                var installedSize: Long = 0

                while (true) {
                    val numBytesRead = sis.read(readBuffer, 0, readBuffer.size)
                    if (numBytesRead <= 0) break

                    if (installationJob.isCancelled) return

                    buffer!!.position(0)
                    buffer.put(readBuffer, 0, numBytesRead)
                    submitFromAshmem(numBytesRead.toLong())
                    installedSize += numBytesRead.toLong()
                    publishProgress(installedSize, partitionSize, partition)
                }
                publishProgress(partitionSize, partitionSize, partition)
            }
        }

        if (!closePartition()) {
            Log.d(tag, "Failed to install $partition partition")
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
        } else {
            Log.d(tag, "Partition $partition installed, readOnly: $readOnly, partitionSize: $partitionSize")
        }
    }

    fun performInstallationFromUri(uri: Uri) {
        try {
            val inputStream = openInputStream(uri)
            val zipInputStream = ZipInputStream(inputStream)

            var entry: ZipEntry?
            while (zipInputStream.nextEntry.also { entry = it } != null) {
                val fileName = entry!!.name
                if (shouldInstallEntry(fileName)) {
                    installImageFromAnEntry(entry!!, zipInputStream)
                } else {
                    Log.d(tag, "$fileName installation is not supported, skipping it.")
                }
                if (installationJob.isCancelled) break
            }

            if (!installationJob.isCancelled) {
                finishInstallation()
                Log.d(tag, "Installation finished successfully.")
                onInstallationSuccess()
            }
        } catch (e: Exception) {
            Log.e(tag, "Installation failed with exception: ${e.message}")
            onInstallationError(InstallationStep.ERROR_INSTALLATION_FAILED, e.message ?: "Unknown error")
        }
    }

    private fun installImageFromAnEntry(entry: ZipEntry, inputStream: InputStream) {
        val fileName = entry.name
        Log.d(tag, "Installing: $fileName")
        val partitionName = fileName.substring(0, fileName.length - 4)
        val uncompressedSize = entry.size
        installImage(partitionName, uncompressedSize, inputStream)
    }

    fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)!!
    }

    fun createNewPartition(partition: String, partitionSize: Long, readOnly: Boolean) {
        val result = createPartition(partition, partitionSize, readOnly)
        if (result != IGsiService.INSTALL_OK) {
            Log.d(tag, "Failed to create $partition partition, error code: $result")
            installationJob.cancel()
            onInstallationError(InstallationStep.ERROR_CREATE_PARTITION, partition)
        }
    }

    override fun invoke() {
        // Reserved for any future invocation-related functionality.
    }
}

package main

import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

class Controller : Initializable {
    @FXML
    lateinit var root: VBox
    @FXML
    lateinit var dstCanvasL: SimpleGraph
    @FXML
    lateinit var dstCanvasR: SimpleGraph
    @FXML
    lateinit var irSampleCanvasL: SimpleGraph
    @FXML
    lateinit var irSampleCanvasR: SimpleGraph
    @FXML
    lateinit var irFFTCanvasL: SimpleGraph
    @FXML
    lateinit var irFFTCanvasR: SimpleGraph

    var irSampleL = FloatArray(0)
    var irSampleR = FloatArray(0)

    var irFFTL = arrayOf(Complex(0.0))
    var irFFTR = arrayOf(Complex(0.0))

    var srcGrabber: FFmpegFrameGrabber? = null

    override fun initialize(location: URL?, resources: ResourceBundle?) {

    }

    fun onImpulseSelect(actionEvent: ActionEvent) {
        val file = FileChooser().showOpenDialog(root.scene.window) ?: return

        val grabber = FFmpegFrameGrabber(file)
        grabber.sampleMode = FrameGrabber.SampleMode.FLOAT
        grabber.start()

        irSampleL = FloatArray((grabber.lengthInTime / 1000_000.0 * grabber.sampleRate).toInt() + 1)
        irSampleR = FloatArray((grabber.lengthInTime / 1000_000.0 * grabber.sampleRate).toInt() + 1)

        var read = 0
        while (true) {
            val buf = grabber.grabSamples()?.samples?.get(0) as? FloatBuffer ?: break
            val tmpArray = FloatArray(buf.limit())
            buf.get(tmpArray)

            val tmpBufL = tmpArray.filterIndexed { index, _ -> index % 2 == 0 }.toFloatArray()
            irSampleL.replaceRange(read, read + tmpBufL.size, tmpBufL)

            val tmpBufR = tmpArray.filterIndexed { index, _ -> index % 2 == 1 }.toFloatArray()
            irSampleR.replaceRange(read, read + tmpBufR.size, tmpBufR)

            read += buf.limit() / 2
        }

        irSampleL = irSampleL.toPower2()
        irSampleR = irSampleR.toPower2()

        val fft = FastFourierTransformer(DftNormalization.STANDARD)
        irFFTL = fft.transform(irSampleL.map { it.toDouble() }.toDoubleArray(), TransformType.FORWARD)
        irFFTR = fft.transform(irSampleR.map { it.toDouble() }.toDoubleArray(), TransformType.FORWARD)

        irSampleCanvasL.data = irSampleL.mapIndexed { index, value -> SimpleGraph.DataPoint(index.toDouble(), value.toDouble()) }
        irSampleCanvasR.data = irSampleR.mapIndexed { index, value -> SimpleGraph.DataPoint(index.toDouble(), value.toDouble()) }

        irFFTCanvasL.data = irFFTL.mapIndexed { index, complex -> SimpleGraph.DataPoint(index.toDouble(), 20 * Math.log10(Math.sqrt(complex.real * complex.real + complex.imaginary * complex.imaginary))) }
        irFFTCanvasR.data = irFFTR.mapIndexed { index, complex -> SimpleGraph.DataPoint(index.toDouble(), 20 * Math.log10(Math.sqrt(complex.real * complex.real + complex.imaginary * complex.imaginary))) }

        irFFTL = Array(irFFTL.size, { _ -> Complex(0.0) }) + irFFTL
        irFFTR = Array(irFFTR.size, { _ -> Complex(0.0) }) + irFFTR

    }

    var tmpBuffer: FloatBuffer? = null
    private fun readSamples(size: Int): FloatArray? {
        val result = FloatArray(size)
        var read = 0
        while (read < size) {
            if (tmpBuffer == null || tmpBuffer?.remaining() == 0)
                tmpBuffer = srcGrabber?.grabSamples()?.samples?.get(0) as? FloatBuffer ?: break

            val toRead = Math.min(tmpBuffer?.remaining() ?: 0, size - read)
            tmpBuffer?.get(result, read, toRead)
            read += toRead
        }
        return if (read > 0) result else null
    }

    fun FloatArray.replaceRange(start: Int, end: Int, replacement: FloatArray) {
        if (end - start != replacement.size) throw Exception("置き換えの配列と範囲の大きさが一致しません")
        for (i in start until end)
            this[i] = replacement[i - start]
    }

    fun FloatArray.toPower2(): FloatArray {
        var i = 1.0
        while (this.size > Math.pow(2.0, i)) {
            i++
        }

        return this + FloatArray(Math.pow(2.0, i).toInt() - this.size)
    }

    fun onSrcSelect(actionEvent: ActionEvent) {
        val file = FileChooser().showOpenDialog(root.scene.window) ?: return

        srcGrabber = FFmpegFrameGrabber(file)
        srcGrabber?.sampleMode = FrameGrabber.SampleMode.FLOAT
        srcGrabber?.start()
    }

    fun play(actionEvent: ActionEvent) {
        Thread({
            val audioFormat = AudioFormat((srcGrabber?.sampleRate?.toFloat() ?: 0f), 16, 2, true, false)

            val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
            val audioLine = AudioSystem.getLine(info) as SourceDataLine
            audioLine.open(audioFormat)
            audioLine.start()

            val operationQueue = SerializedOperationQueue()

            val fft = FastFourierTransformer(DftNormalization.STANDARD)
            var prevSampleL = FloatArray(irSampleL.size)
            var prevSampleR = FloatArray(irSampleR.size)
            while (true) {
                val sample = readSamples(irSampleL.size * 2) ?: break

                val sampleL = sample.filterIndexed { index, _ -> index % 2 == 0 }.toFloatArray()
                val srcL = prevSampleL + sampleL

                val sampleR = sample.filterIndexed { index, _ -> index % 2 == 1 }.toFloatArray()
                val srcR = prevSampleR + sampleR

                var fftL = fft.transform(srcL.map { it.toDouble() }.toDoubleArray(), TransformType.FORWARD)
                var fftR = fft.transform(srcR.map { it.toDouble() }.toDoubleArray(), TransformType.FORWARD)

                fftL = fftL.mapIndexed { index, complex -> irFFTL[index].multiply(complex) }.toTypedArray()
                fftR = fftR.mapIndexed { index, complex -> irFFTR[index].multiply(complex) }.toTypedArray()

                val dstL = fft.transform(fftL, TransformType.INVERSE)
                val dstR = fft.transform(fftR, TransformType.INVERSE)

                val dst = FloatArray(dstL.size + dstR.size)
                for (i in 0 until dstL.size) {
                    dst[i * 2] = dstL[i].real.toFloat()
                    dst[i * 2 + 1] = dstR[i].real.toFloat()
                }

                val max = dst.max()?:1f
                println(max)
                for(i in 0 until dst.size)
                    dst[i]/=max

                val buf = ByteBuffer.allocate(dst.size).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until dst.size / 2) {
                    buf.putShort((dst[i] * Short.MAX_VALUE).toShort())
                }
                buf.position(0)

                operationQueue.push {
                    val sL = dstL.mapIndexed { index, complex -> SimpleGraph.DataPoint(index.toDouble(),complex.real) }
                    val sR = dstR.mapIndexed { index, complex -> SimpleGraph.DataPoint(index.toDouble(),complex.real) }

                    Platform.runLater {
                        dstCanvasL.data = sL
                        dstCanvasR.data = sR
                    }

                    val arr = buf.array()
                    audioLine.write(arr, 0, arr.size / 4 * 4)
                }




                prevSampleL = sampleL
                prevSampleR = sampleR
            }


        }).start()
    }

}
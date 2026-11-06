package eu.berdosi.app.heartbeat;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.TextureView;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class ChartDrawer
{
    private  final TextureView chartTextureView;
    private final Paint paint = new Paint();
    private final Paint peakPaint = new Paint();
    private final Paint fillBackground = new Paint();
    private final Paint fillWarmup = new Paint();

    ChartDrawer(TextureView chartTextureView) {
        this.chartTextureView = chartTextureView;


        paint.setStyle(Paint.Style.STROKE);

        paint.setColor(Color.GREEN);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(2);

        peakPaint.setStyle(Paint.Style.STROKE);
        peakPaint.setColor(Color.RED);
        peakPaint.setStrokeWidth(2);

        fillBackground.setStyle(Paint.Style.FILL);
        fillBackground.setColor(Color.BLACK);

        fillWarmup.setStyle(Paint.Style.FILL);
        fillWarmup.setColor(Color.rgb(64, 64, 64));
    }

    /** Blanks the chart, for a new reading. */
    void clear() {
        Canvas chartCanvas = chartTextureView.lockCanvas();
        if (chartCanvas == null) return;
        chartCanvas.drawPaint(fillBackground);
        chartTextureView.unlockCanvasAndPost(chartCanvas);
    }

    /**
     * @param warmup    samples that are shown but not analysed, on a grey background
     * @param data      analysed samples, following the warm-up ones
     * @param peakTimes timestamps (ms) of detected beats, drawn as vertical red lines
     */
    void draw(CopyOnWriteArrayList<Measurement<Float>> warmup,
              CopyOnWriteArrayList<Measurement<Float>> data,
              List<Long> peakTimes) {
        Canvas chartCanvas = chartTextureView.lockCanvas();

        if (chartCanvas == null) return;

        chartCanvas.drawPaint(fillBackground);

        float width = (float)chartCanvas.getWidth();
        float height = (float)chartCanvas.getHeight();
        int warmupAmount = warmup.size();
        int dataAmount = data.size();
        int total = warmupAmount + dataAmount;

        if (total > 0) {
            if (warmupAmount > 0) {
                chartCanvas.drawRect(0, 0, width * warmupAmount / total, height, fillWarmup);
            }

            // detected peaks: data is in time order, so one pass places every marker
            int dataIndex = 0;
            for (long peakTime : peakTimes) {
                while (dataIndex < dataAmount && data.get(dataIndex).timestamp.getTime() < peakTime) dataIndex++;
                if (dataIndex >= dataAmount) break;
                float x = width * (warmupAmount + dataIndex) / total;
                chartCanvas.drawLine(x, 0, x, height, peakPaint);
            }

            // each part is scaled on its own: warm-up values are far outside the real range
            drawSegment(chartCanvas, warmup, 0, total, width, height);
            drawSegment(chartCanvas, data, warmupAmount, total, width, height);
        }

        chartTextureView.unlockCanvasAndPost(chartCanvas);
    }

    private void drawSegment(Canvas canvas, List<Measurement<Float>> segment, int offset, int total,
                             float width, float height) {
        if (segment.size() < 2) return;

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (Measurement<Float> dataPoint : segment) {
            float value = dataPoint.measurement;
            if (Float.isNaN(value)) continue; // a store with a single value has no range yet
            if (value < min) min = value;
            if (value > max) max = value;
        }
        float range = max - min;

        Path graphPath = new Path();
        for (int i = 0; i < segment.size(); i++) {
            float value = segment.get(i).measurement;
            float y = (range > 0 && !Float.isNaN(value)) ? height * (value - min) / range : height / 2;
            float x = width * (offset + i) / total;
            if (i == 0) {
                graphPath.moveTo(x, y);
            } else {
                graphPath.lineTo(x, y);
            }
        }
        canvas.drawPath(graphPath, paint);
    }

}

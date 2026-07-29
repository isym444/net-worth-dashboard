package com.isym444.networth.service;

import com.isym444.networth.model.NetWorthSnapshot;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

@Service
public class ChartService {

    private static final Color SURFACE = new Color(0x12, 0x16, 0x1f);
    private static final Color BORDER = new Color(0x23, 0x28, 0x38);
    private static final Color TEXT = new Color(0xe8, 0xea, 0xed);
    private static final Color TEXT_MUTED = new Color(0x8b, 0x92, 0xa3);
    private static final Color ACCENT = new Color(0x34, 0xd3, 0x99);
    private static final Color TRADING212_COLOR = new Color(0x60, 0xa5, 0xfa);
    private static final Color CRYPTO_COLOR = new Color(0xfb, 0xbf, 0x24);

    public byte[] renderNetWorthChart(List<NetWorthSnapshot> rows, String currency) throws IOException {
        TimeSeries t212Series = new TimeSeries("Trading212");
        TimeSeries cryptoSeries = new TimeSeries("Crypto.com");
        TimeSeries grandSeries = new TimeSeries("Net worth");

        for (NetWorthSnapshot row : rows) {
            Millisecond point = new Millisecond(Date.from(row.getTimestamp()));
            t212Series.addOrUpdate(point, row.getT212Total());
            cryptoSeries.addOrUpdate(point, row.getCryptoTotal());
            grandSeries.addOrUpdate(point, row.getGrandTotal());
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(t212Series);
        dataset.addSeries(cryptoSeries);
        dataset.addSeries(grandSeries);

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                "Net worth over time (" + currency + ")",
                "Date",
                currency,
                dataset,
                true,
                false,
                false
        );

        applyDarkTheme(chart);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartUtils.writeChartAsPNG(out, chart, 1000, 550);
        return out.toByteArray();
    }

    private void applyDarkTheme(JFreeChart chart) {
        chart.setBackgroundPaint(SURFACE);
        chart.setBorderVisible(false);

        chart.getTitle().setPaint(TEXT);
        chart.getTitle().setFont(new Font("SansSerif", Font.BOLD, 16));

        LegendTitle legend = chart.getLegend();
        if (legend != null) {
            legend.setBackgroundPaint(SURFACE);
            legend.setItemPaint(TEXT_MUTED);
        }

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(SURFACE);
        plot.setDomainGridlinePaint(BORDER);
        plot.setRangeGridlinePaint(BORDER);
        plot.setOutlinePaint(BORDER);

        ValueAxis domainAxis = plot.getDomainAxis();
        domainAxis.setAxisLinePaint(BORDER);
        domainAxis.setTickMarkPaint(BORDER);
        domainAxis.setTickLabelPaint(TEXT_MUTED);
        domainAxis.setLabelPaint(TEXT_MUTED);

        ValueAxis rangeAxis = plot.getRangeAxis();
        rangeAxis.setAxisLinePaint(BORDER);
        rangeAxis.setTickMarkPaint(BORDER);
        rangeAxis.setTickLabelPaint(TEXT_MUTED);
        rangeAxis.setLabelPaint(TEXT_MUTED);

        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        Color[] seriesColors = { TRADING212_COLOR, CRYPTO_COLOR, ACCENT };
        float[] seriesStrokes = { 2.0f, 2.0f, 2.5f };
        for (int i = 0; i < seriesColors.length; i++) {
            renderer.setSeriesPaint(i, seriesColors[i]);
            renderer.setSeriesStroke(i, new BasicStroke(seriesStrokes[i]));
            renderer.setSeriesShapesVisible(i, false);
        }
    }
}

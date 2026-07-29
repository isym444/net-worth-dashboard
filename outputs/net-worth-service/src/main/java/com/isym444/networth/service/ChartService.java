package com.isym444.networth.service;

import com.isym444.networth.model.NetWorthSnapshot;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

@Service
public class ChartService {

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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChartUtils.writeChartAsPNG(out, chart, 1000, 550);
        return out.toByteArray();
    }
}

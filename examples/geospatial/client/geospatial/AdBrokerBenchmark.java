/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package geospatial;

import java.io.IOException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.voltdb.CLIConfig;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;
import org.voltdb.client.ClientStatusListenerExt;
import org.voltdb.client.NullCallback;
import org.voltdb.client.ProcCallException;
import org.voltdb.types.GeographyPointValue;

/**
 * This class simulates an ad broker application, with two streams of
 * data entering a VoltDB cluster:
 *   - The logins of devices from a particular place
 *   - The bids generated by businesses wishing to advertise on those devices
 *
 *  This class initiates several tasks in parallel:
 *   - The simulation of businesses making bids to show ads
 *   - The simulation of devices requesting ads
 *   - Periodic display of stats (e.g., counts of ads displayed for the top 5 businesses)
 *   - Periodic deletion of expired bids and historical data.  (In a real app,
 *     this data would probably be exported.)
 */
public class AdBrokerBenchmark {

    // handy, rather than typing this out several times
    static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    // validated command line configuration
    final AdBrokerConfig m_config;

    // Reference to the database connection we will use
    final Client m_client;

    // Instance of Random for generating randomized data.
    Random m_rand;

    // Benchmark start time
    long m_benchmarkStartTS;

    // Statistics manager objects from the client
    final ClientStatsContext m_periodicStatsContext;
    final ClientStatsContext m_fullStatsContext;

    final ScheduledExecutorService m_scheduler = Executors.newScheduledThreadPool(4);

    // Parameters of generated bids:

    // The bid generator will generate one bid (where the advertiser
    // is chosen randomly) at a rate of BID_FREQUENCY_PER_SECOND.
    // Each generated bid will begin at the current timestamp and last
    // for BID_DURATION_SECONDS.  Thus, at any given time we'll have
    // (BID_DURATION_SECONDS * BID_FREQUENCY_PER_SECOND) active bids
    // in the system.
    static final int BID_DURATION_SECONDS = 20;
    static final int BID_FREQUENCY_PER_SECOND = 5;

    // Before the benchmark begins, the bid generator will randomly
    // generate NUM_BID_REGIONS polygons.  Each generated bid will
    // randomly choose one of these polygons as its bid region.
    //
    // The number chosen here is based in the number of active bids
    // that exist at any given time.
    static final int NUM_BID_REGIONS = BID_DURATION_SECONDS * BID_FREQUENCY_PER_SECOND;

    // A bounding box that will contain all the bid regions, and the points
    // corresponding to devices.  This is a 2-mile-by-2-mile square centered at (0, 0)
    static final double BID_AREA_LNG_MIN = -0.014492753623;
    static final double BID_AREA_LNG_MAX =  0.014492753623;
    static final double BID_AREA_LAT_MIN = -0.014492753623;
    static final double BID_AREA_LAT_MAX =  0.014492753623;

    // Devices will log in with a device id between 0 (inclusive) and
    // NUM_DEVICES (exclusive).
    static final int NUM_DEVICES = 1000000;

    /**
     * Uses included {@link CLIConfig} class to
     * declaratively state command line options with defaults
     * and validation.
     */
    static class AdBrokerConfig extends CLIConfig {
        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 60;

        @Option(desc = "Warmup duration, in seconds.")
        int warmup = 5;

        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "Maximum TPS rate for benchmark.")
        int ratelimit = 10000;

        @Option(desc = "Report latency for async benchmark run.")
        boolean latencyreport = true;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        @Option(desc = "User name for connection.")
        String user = "";

        @Option(desc = "Password for connection.")
        String password = "";

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (warmup < 0) exitWithMessageAndUsage("warmup must be >= 0");
            if (displayinterval <= 0) exitWithMessageAndUsage("displayinterval must be > 0");
            if (ratelimit <= 0) exitWithMessageAndUsage("ratelimit must be > 0");
        }
    }

    /**
     * Provides a callback to be notified on node failure.
     * This example only logs the event.
     */
    class StatusListener extends ClientStatusListenerExt {
        @Override
        public void connectionLost(String hostname, int port, int connectionsLeft, DisconnectCause cause) {
            // if the benchmark is still active
            if ((System.currentTimeMillis() - m_benchmarkStartTS) < (m_config.duration * 1000)) {
                System.err.printf("Connection to %s:%d was lost.\n", hostname, port);
            }
        }
    }

    /**
     * Constructor for benchmark instance.
     * Configures VoltDB client and prints configuration.
     *
     * @param config Parsed & validated CLI options.
     */
    public AdBrokerBenchmark(AdBrokerConfig config) {
        m_config = config;

        ClientConfig clientConfig = new ClientConfig(config.user, config.password, new StatusListener());
        clientConfig.setMaxTransactionsPerSecond(config.ratelimit);

        m_client = ClientFactory.createClient(clientConfig);

        m_rand = new Random(777);

        m_periodicStatsContext = m_client.createStatsContext();
        m_fullStatsContext = m_client.createStatsContext();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Command Line Configuration");
        System.out.println(HORIZONTAL_RULE);
        System.out.println(config.getConfigDumpString());
        if (config.latencyreport && config.ratelimit == Integer.MAX_VALUE) {
            System.out.println("WARNING: Option latencyreport is ON, and no rate limit is set.  "
                    + "Expect high latencies to be reported.\n");
        }
    }

    /**
     * Connect to a single server with retry. Limited exponential backoff.
     * No timeout. This will run until the process is killed if it's not
     * able to connect.
     *
     * @param server hostname:port or just hostname (hostname can be ip).
     */
    static void connectToOneServerWithRetry(Client client, String server) {
        int sleep = 1000;
        while (true) {
            try {
                client.createConnection(server);
                break;
            }
            catch (Exception e) {
                System.err.printf("Connection failed - retrying in %d second(s).\n", sleep / 1000);
                try { Thread.sleep(sleep); } catch (Exception interruted) {}
                if (sleep < 8000) sleep += sleep;
            }
        }
        System.out.printf("Connected to VoltDB node at: %s.\n", server);
    }

    /**
     * Connect to a set of servers in parallel. Each will retry until
     * connection. This call will block until all have connected.
     *
     * @param servers A comma separated list of servers using the hostname:port
     * syntax (where :port is optional).
     * @throws InterruptedException if anything bad happens with the threads.
     */
    static void connect(final Client client, String servers) throws InterruptedException {
        System.out.println("Connecting to VoltDB...");

        String[] serverArray = servers.split(",");
        final CountDownLatch connections = new CountDownLatch(serverArray.length);

        // use a new thread to connect to each server
        for (final String server : serverArray) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    connectToOneServerWithRetry(client, server);
                    connections.countDown();
                }
            }).start();
        }
        // block until all have connected
        connections.await();
    }

    /**
     * Add a task to the scheduler to print statistics to the console
     * at regular intervals.
     */
    public void schedulePeriodicStats() {
        Runnable statsPrinter = new Runnable() {
            @Override
            public void run() { printStatistics(); }
        };
        m_scheduler.scheduleWithFixedDelay(statsPrinter,
                m_config.displayinterval,
                m_config.displayinterval,
                TimeUnit.SECONDS);
    }

    /**
     * Print stats for the last displayinterval seconds to the console.
     */
    public synchronized void printStatistics() {
        ClientStats stats = m_periodicStatsContext.fetchAndResetBaseline().getStats();
        long time = Math.round((stats.getEndTimestamp() - m_benchmarkStartTS) / 1000.0);

        System.out.printf("%02d:%02d:%02d ", time / 3600, (time / 60) % 60, time % 60);
        System.out.printf("Throughput %d/s, ", stats.getTxnThroughput());
        System.out.printf("Aborts/Failures %d/%d",
                stats.getInvocationAborts(), stats.getInvocationErrors());
        if(m_config.latencyreport) {
            System.out.printf(", Avg/95%% Latency %.2f/%.2fms", stats.getAverageLatency(),
                stats.kPercentileLatencyAsDouble(0.95));
        }
        System.out.printf("\n");

        VoltTable[] tables = null;
        try {
            tables = m_client.callProcedure("GetStats", m_config.displayinterval).getResults();
        } catch (IOException | ProcCallException e) {
            e.printStackTrace();
            System.exit(1);
        }

        VoltTable hits = tables[0];
        hits.advanceRow();
        assert(hits.getLong(0) == 0);
        long unmetRequests = hits.getLong(1);
        hits.advanceRow();
        assert(hits.getLong(0) == 1);
        long metRequests = hits.getLong(1);
        long totalRequests = unmetRequests + metRequests;
        double percentMet = (((double)metRequests) / totalRequests) * 100.0;
        System.out.printf("Total number of ad requests: %d, %3.2f%% resulted in an ad being served\n",
                totalRequests, percentMet);

        VoltTable recentAdvertisers = tables[1];
        System.out.println("\nTop 5 advertisers of the last " + m_config.displayinterval + " seconds, "
                + "by sorted on the sum of dollar amounts of bids won:");
        System.out.println("Advertiser                                   Revenue   Count");
        System.out.println("----------                                   -------   -----");
        while (recentAdvertisers.advanceRow()) {
            System.out.printf("%-40s  %9.2f    %d\n",
                    recentAdvertisers.getString(0),
                    recentAdvertisers.getDouble(1),
                    recentAdvertisers.getLong(2));
        }
        System.out.println();
    }

    /**
     * Prints some summary statistics about performance.
     *
     * @throws Exception if anything unexpected happens.
     */
    public synchronized void printResults() throws Exception {
        ClientStats stats = m_fullStatsContext.fetch().getStats();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Client Workload Statistics");
        System.out.println(HORIZONTAL_RULE);

        System.out.printf("Average throughput:            %,9d txns/sec\n", stats.getTxnThroughput());
        if(m_config.latencyreport) {
            System.out.printf("Average latency:               %,9.2f ms\n", stats.getAverageLatency());
            System.out.printf("10th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.1));
            System.out.printf("25th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.25));
            System.out.printf("50th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.5));
            System.out.printf("75th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.75));
            System.out.printf("90th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.9));
            System.out.printf("95th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.95));
            System.out.printf("99th percentile latency:       %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.99));
            System.out.printf("99.5th percentile latency:     %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.995));
            System.out.printf("99.9th percentile latency:     %,9.2f ms\n", stats.kPercentileLatencyAsDouble(.999));

            System.out.print("\n" + HORIZONTAL_RULE);
            System.out.println(" System Server Statistics");
            System.out.println(HORIZONTAL_RULE);
            System.out.printf("Reported Internal Avg Latency: %,9.2f ms\n", stats.getAverageInternalLatency());

            System.out.print("\n" + HORIZONTAL_RULE);
            System.out.println(" Latency Histogram");
            System.out.println(HORIZONTAL_RULE);
            System.out.println(stats.latencyHistoReport());
        }

        // 4. Write stats to file if requested
        m_client.writeSummaryCSV(stats, m_config.statsfile);
    }

    /**
     * Perform various tasks to end the demo cleanly.
     */
    private void shutdown() {

        // Stop the stats printer, the bid generator and the nibble deleter.
        m_scheduler.shutdown();

        try {
            m_scheduler.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            // block until all outstanding txns return
            m_client.drain();
            // close down the client connections
            m_client.close();
        }
        catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Produce a random longitude/latitude point within the bounding box
     * where advertisers are placing bids.
     *
     * @return a random point
     */
    private GeographyPointValue getRandomPoint() {
        double lngRange = BID_AREA_LNG_MAX - BID_AREA_LNG_MIN;
        double lng = BID_AREA_LNG_MIN + lngRange * m_rand.nextDouble();

        double latRange = BID_AREA_LAT_MAX - BID_AREA_LAT_MIN;
        double lat = BID_AREA_LAT_MIN + latRange * m_rand.nextDouble();

        return new GeographyPointValue(lng, lat);
    }

    /**
     * Invoke the stored procedure GetHighestBidForLocation, which, given a random
     * point, returns the id of the bid that has the highest dollar amount.
     */
    private void requestAd() {
        long deviceId = Math.abs(m_rand.nextLong()) % AdBrokerBenchmark.NUM_DEVICES;
        GeographyPointValue point = getRandomPoint();

        try {
            m_client.callProcedure(new NullCallback(), "GetHighestBidForLocation", deviceId, point);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Core benchmark code.
     * Connect. Initialize. Run the loop. Cleanup. Print Results.
     *
     * @throws Exception if anything unexpected happens.
     */
    public void runBenchmark() throws Exception {
        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Setup & Initialization");
        System.out.println(HORIZONTAL_RULE);

        // connect to one or more servers, loop until success
        connect(m_client, m_config.servers);

        System.out.print("\n\n" + HORIZONTAL_RULE);
        System.out.println(" Starting Benchmark");
        System.out.println(HORIZONTAL_RULE);

        System.out.println("\nStarting bid generator\n");
        BidGenerator bidGenerator = new BidGenerator(m_client);
        long usDelay = (long) ((1.0 / BID_FREQUENCY_PER_SECOND) * 1000000.0);
        m_scheduler.scheduleWithFixedDelay(bidGenerator, 0, usDelay, TimeUnit.MICROSECONDS);

        System.out.println("\nStarting nibble deleter to delete expired data\n");
        NibbleDeleter deleter = new NibbleDeleter(m_client, m_config.displayinterval + 1);
        // Run once a second
        m_scheduler.scheduleWithFixedDelay(deleter, 1, 1, TimeUnit.SECONDS);

        System.out.println("\nWarming up...");
        final long warmupEndTime = System.currentTimeMillis() + (1000l * m_config.warmup);
        while (warmupEndTime > System.currentTimeMillis()) {
            requestAd();
        }

        // reset the stats
        m_fullStatsContext.fetchAndResetBaseline();
        m_periodicStatsContext.fetchAndResetBaseline();

        // print periodic statistics to the console
        m_benchmarkStartTS = System.currentTimeMillis();
        schedulePeriodicStats();

        // Run the benchmark loop for the requested duration
        // The throughput may be throttled depending on client configuration
        System.out.println("\nRunning benchmark...");
        final long benchmarkEndTime = System.currentTimeMillis() + (1000l * m_config.duration);
        while (benchmarkEndTime > System.currentTimeMillis()) {
            requestAd();
        }

        printResults();
        shutdown();
    }

    /**
     * Main routine creates a benchmark instance and kicks off the run method.
     *
     * @param args Command line arguments.
     * @throws Exception if anything goes wrong.
     * @see {@link AdBrokerConfig}
     */
    public static void main(String[] args) throws Exception {
        // create a configuration from the arguments
        AdBrokerConfig config = new AdBrokerConfig();
        config.parse(AdBrokerBenchmark.class.getName(), args);

        AdBrokerBenchmark benchmark = new AdBrokerBenchmark(config);
        benchmark.runBenchmark();
    }
}

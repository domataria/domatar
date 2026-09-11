package com.domatar.log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.domatar.core.DomatarConfig;
import com.domatar.db.OpLogDb;

/**
 * Host-local visit / orphan-edge GC. One daemon thread per JVM.
 */
public final class OpLogJanitor
{
  private static final Logger LOG = Logger.getLogger(OpLogJanitor.class.getName());

  private static final long MIN_PERIOD_MS = 10_000L;
  private static final long MAX_PERIOD_MS = 60_000L;

  private static final Object LOCK = new Object();
  private static ScheduledExecutorService exec;

  private OpLogJanitor() {}

  public static void start()
  {
    synchronized (LOCK)
    {
      if (exec != null && !exec.isShutdown())
        return;

      long period = Math.min(MAX_PERIOD_MS, OpLog.visitTtlMs());
      if (period < MIN_PERIOD_MS)
        period = MIN_PERIOD_MS;

      exec = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "OpLogJanitor");
        t.setDaemon(true);
        return t;
      });
      exec.scheduleAtFixedRate(OpLogJanitor::tick, period, period,
          TimeUnit.MILLISECONDS);
      LOG.info("OpLogJanitor started periodMs=" + period);
    }
  }

  public static void stop()
  {
    synchronized (LOCK)
    {
      if (exec == null)
        return;

      exec.shutdownNow();
      exec = null;
    }
  }

  private static void tick()
  {
    try
    {
      OpLogDb.gcHost(DomatarConfig.getHstId(), OpLog.nowMs());
    }
    catch (final Exception e)
    {
      LOG.log(Level.WARNING, "OpLogJanitor.gcHost failed", e);
    }
  }
}

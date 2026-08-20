/*
 * Copyright (c) 2024 Domatar
 */

package com.domatar.app;

import com.domatar.install.AppInstall;

import java.net.URL;

/**
 * Immutable runtime record for a loaded app.
 *
 * Created by AppLoader during ServletContext initialisation and stored in
 * AppRegistry.  Consumers reach an app's install instance or ClassLoader
 * exclusively through this record.
 */
public final class App
{
  public final String      appId;
  public final ClassLoader classLoader;
  public final AppManifest manifest;
  public final AppInstall  installInstance;
  public final URL         jarUrl;

  public App(String      appId,
             ClassLoader classLoader,
             AppManifest manifest,
             AppInstall  installInstance,
             URL         jarUrl)
  {
    this.appId           = appId;
    this.classLoader     = classLoader;
    this.manifest        = manifest;
    this.installInstance = installInstance;
    this.jarUrl          = jarUrl;
  }
}

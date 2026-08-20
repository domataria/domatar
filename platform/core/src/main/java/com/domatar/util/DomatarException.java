package com.domatar.util;

// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;

public class DomatarException extends Exception
{
  /**
   *
   */
  private static final long serialVersionUID = 9204252017279148538L;
  private final Exception wrappedException;
  // private static final Logger logger = LogManager.getLogger();

  public DomatarException (Exception exception)
  {
    super();
    wrappedException = exception;
    // logger.error(exception.getMessage());
  }

  public DomatarException (String message)
  {
    super(message);
    wrappedException = null;
  }

  public DomatarException (String message, Exception exception)
  {
    super(message);
    wrappedException = exception;
  }

  @Override
  public String getMessage ()
  {
    final String wrappedMessage = wrappedException != null
                                  ? wrappedException.getMessage()
                                  : null;

    final String thisMessage = super.getMessage();

    if (thisMessage == null)
      return wrappedMessage;
    else if (wrappedMessage == null)
      return thisMessage;
    else
      return (thisMessage + " wraps: " + wrappedMessage);
  }

  public Exception getWrappedException ()
  {
    return wrappedException;
  }

  @Override
  public String toString ()
  {
    return getMessage();
  }
}

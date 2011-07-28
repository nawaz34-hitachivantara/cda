package pt.webdetails.cda.dataaccess;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import javax.swing.table.TableModel;

import net.sf.ehcache.Cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Element;
import org.pentaho.reporting.engine.classic.core.ParameterDataRow;


import pt.webdetails.cda.CdaBoot;
import pt.webdetails.cda.cache.TableCacheKey;
import pt.webdetails.cda.connections.Connection;
import pt.webdetails.cda.connections.ConnectionCatalog;
import pt.webdetails.cda.connections.DummyConnection;
import pt.webdetails.cda.query.QueryOptions;
import pt.webdetails.cda.settings.UnknownConnectionException;
import pt.webdetails.cda.utils.TableModelUtils;
import pt.webdetails.cda.utils.Util;

/**
 * Implementation of the SimpleDataAccess
 * User: pedro
 * Date: Feb 3, 2010
 * Time: 11:04:10 AM
 */
public abstract class SimpleDataAccess extends AbstractDataAccess
{

  private static final Log logger = LogFactory.getLog(SimpleDataAccess.class);
  protected String connectionId;
  protected String query;
  private static final String QUERY_TIME_THRESHOLD_PROPERTY = "pt.webdetails.cda.QueryTimeThreshold";
  private static int queryTimeThreshold = getQueryTimeThresholdFromConfig(3600);//seconds


  public SimpleDataAccess()
  {
  }


  public SimpleDataAccess(final Element element)
  {

    super(element);
    connectionId = element.attributeValue("connection");
    query = element.selectSingleNode("./Query").getText();

  }


  /**
   * 
   * @param id DataAccess ID
   * @param name
   * @param connectionId
   * @param query
   */
  public SimpleDataAccess(String id, String name, String connectionId, String query)
  {
    super(id, name);
    this.query = query;
    this.connectionId = connectionId;
  }


  protected synchronized TableModel queryDataSource(final QueryOptions queryOptions) throws QueryException
  {

    final Cache cache = getCache();

    // Get parameters from definition and apply it's values
    final List<Parameter> parameters = (List<Parameter>) getParameters().clone();

    for (final Parameter parameter : parameters)
    {
      final Parameter parameterPassed = queryOptions.getParameter(parameter.getName());
      if (parameter.getAccess().equals(Parameter.Access.PUBLIC) && parameterPassed != null)
      {
        //parameter.setStringValue(parameterPassed.getStringValue());
        try
        {
          parameterPassed.inheritDefaults(parameter);
          parameter.setValue(parameterPassed.getValue());
        }
        catch (InvalidParameterException e){
          throw new QueryException("Error parsing parameters ", e);
        } 
      }
      else
      {
        parameter.setValue(parameter.getDefaultValue());
      }
    }


    final ParameterDataRow parameterDataRow;
    try
    {
      parameterDataRow = Parameter.createParameterDataRowFromParameters(parameters);
    }
    catch (InvalidParameterException e)
    {
      throw new QueryException("Error parsing parameters ", e);
    }

    // create the cache-key which is both query and parameter values
    TableCacheKey key;
    TableModel tableModelCopy;
    try
    {
      try
      {
        final Connection connection;
        if (getConnectionType() == ConnectionCatalog.ConnectionType.NONE)
        {
          connection = new DummyConnection();
        }
        else
        {
          connection = getCdaSettings().getConnection(getConnectionId());
        }
        key = new TableCacheKey(connection, getQuery(), parameterDataRow, getExtraCacheKey(), 
            this.getCdaSettings().getId(), queryOptions.getDataAccessId());
      }
      catch (UnknownConnectionException e)
      {
        // I'm sure I'll never be here
        throw new QueryException("Unable to get a Connection for this dataAccess ", e);
      }

      if (isCache())
      {
        ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
        try{
          //make sure we have the right class loader in thread to instantiate cda classes in case DiskStore is used
          Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
          final net.sf.ehcache.Element element = cache.get(key);
          if (element != null && !queryOptions.isCacheBypass()) // Are we explicitly saying to bypass the cache?
          {
            final TableModel cachedTableModel = (TableModel) element.getObjectValue();
            if (cachedTableModel != null)
            {
              // we have a entry in the cache ... great!
              logger.debug("Found tableModel in cache. Returning");
              return cachedTableModel;
            }
          }
        }
        catch(Exception e){
          logger.error("Error while attempting to load from cache, bypassing cache (cause: " + e.getClass() + ")", e);
        }
        finally{
          Thread.currentThread().setContextClassLoader(contextCL);
        }
      }

      //start timing query
      long beginTime = System.currentTimeMillis();

      final TableModel tableModel = postProcessTableModel(performRawQuery(parameterDataRow));

      logIfDurationAboveThreshold(beginTime, getId(), getQuery(), parameters);

      // Copy the tableModel and cache it
      // Handle the TableModel

      tableModelCopy = TableModelUtils.getInstance().copyTableModel(this, tableModel);
    }
    catch (Exception e)
    {
      throw new QueryException("Found an unhandled exception:", e);
    }
    finally
    {
      closeDataSource();
    }

    // put the copy into the cache ...
    if (isCache())
    {
      final net.sf.ehcache.Element storeElement = new net.sf.ehcache.Element(key, tableModelCopy);
      storeElement.setTimeToLive(getCacheDuration());
      cache.put(storeElement);
      cache.flush();
      
      // Print cache status size
      logger.debug("Cache status: " + cache.getMemoryStoreSize() + " in memory, " + 
              cache.getDiskStoreSize() + " in disk");
    }

    // and finally return the copy.
    return tableModelCopy;
  }


  /**
   * @param beginTime When query execution began.
   */
  private void logIfDurationAboveThreshold(final long beginTime, final String queryId, final String query, final List<Parameter> parameters)
  {
    long endTime = System.currentTimeMillis();
    long duration = (endTime - beginTime) / 1000;//precision not an issue: integer op is ok
    if (duration > queryTimeThreshold)
    {
      //log query and duration
      String logMsg = "Query " + queryId + " took " + duration + "s.\n";
      logMsg += "\t Query contents: << " + query.trim() + " >>\n";
      if (parameters.size() > 0)
      {
        logMsg += "\t Parameters: \n";
        for (Parameter parameter : parameters)
        {
          logMsg += "\t\t" + parameter.toString() + "\n";
        }
      }
      logger.debug(logMsg);
    }
  }


  protected TableModel postProcessTableModel(TableModel tm)
  {
    // we can use this method to override the general behavior. By default, no post processing is done
    return tm;
  }


  /**
   * Extra arguments to be used for the cache key. Defaults to null but classes that
   * extend SimpleDataAccess may decide to implement it
   * @return
   */
  protected Object getExtraCacheKey()
  {
    return null;
  }


  protected abstract TableModel performRawQuery(ParameterDataRow parameterDataRow) throws QueryException;


  public abstract void closeDataSource() throws QueryException;


  public String getQuery()
  {
    return query;
  }


  public String getConnectionId()
  {
    return connectionId;
  }


  @Override
  public ArrayList<PropertyDescriptor> getInterface()
  {
    ArrayList<PropertyDescriptor> properties = super.getInterface();
    properties.add(new PropertyDescriptor("query", PropertyDescriptor.Type.STRING, PropertyDescriptor.Placement.CHILD));
    properties.add(new PropertyDescriptor("connection", PropertyDescriptor.Type.STRING, PropertyDescriptor.Placement.ATTRIB));
    properties.add(new PropertyDescriptor("cache", PropertyDescriptor.Type.BOOLEAN, PropertyDescriptor.Placement.ATTRIB));
    properties.add(new PropertyDescriptor("cacheDuration", PropertyDescriptor.Type.NUMERIC, PropertyDescriptor.Placement.ATTRIB));
    return properties;
  }


  private static int getQueryTimeThresholdFromConfig(int defaultValue)
  {
    String strVal = CdaBoot.getInstance().getGlobalConfig().getConfigProperty(QUERY_TIME_THRESHOLD_PROPERTY);
    if (!Util.isNullOrEmpty(strVal))
    {
      try
      {
        return Integer.parseInt(strVal);
      }
      catch (NumberFormatException nfe)
      {
        logger.warn(MessageFormat.format("Could not parse {0} in property {1}, using default {2}.", strVal, QUERY_TIME_THRESHOLD_PROPERTY, defaultValue) );
      }
    }
    return defaultValue;
  }
  
  
}

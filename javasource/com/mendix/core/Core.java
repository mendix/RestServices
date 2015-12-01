package com.mendix.core;

import java.io.InputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.mendix.core.actionmanagement.ActionListener;
import com.mendix.core.actionmanagement.CoreAction;
import com.mendix.core.component.LocalComponent;
import com.mendix.core.component.InternalCore;
import com.mendix.core.conf.Configuration;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.integration.Integration;
import com.mendix.integration.WebserviceException;
import com.mendix.logging.ILogNode;
import com.mendix.logging.LogSubscriber;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.systemwideinterfaces.MendixException;
import com.mendix.systemwideinterfaces.IWebserviceResponse;
import com.mendix.systemwideinterfaces.connectionbus.data.IDataTable;
import com.mendix.systemwideinterfaces.connectionbus.requests.IMetaAssociationSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.types.IGetRequest;
import com.mendix.systemwideinterfaces.connectionbus.requests.types.IOQLTextGetRequest;
import com.mendix.systemwideinterfaces.connectionbus.requests.types.IXPathTextGetRequest;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.ILanguage;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IProfiler;
import com.mendix.systemwideinterfaces.core.ISession;
import com.mendix.systemwideinterfaces.core.IUser;
import com.mendix.systemwideinterfaces.core.UserAction;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

public final class Core
{
	public Core() {}

	private static LocalComponent component;
	private static Integration integration;

	public static void initialize(LocalComponent localComponent, Integration i)
	{
		component = localComponent;
		integration = i;
	}
	
	public static LocalComponent getComponent()
	{
		return component;
	}
	
	public static boolean isInDevelopment()
	{
	  return component.configuration().isInDevelopment();
	}
	
	/**
	 * Returns the id of this server instance.
	 * @return the server id in UUID format.
	 */
	public static String getXASId()
	{
		return component.core().getXASId();
	}
	
	/**
	 * Returns the names of all modeled microflows.
	 * @return all microflow names (format "ModuleName.MicroflowName").
	 */
	public static Set<String> getMicroflowNames()
	{
		return component.core().getMicroflowNames();
	}
	
	/**
	 * Returns all input parameter data types the specified action by name.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @return data types by input parameter name.
	 */
	public static Map<String, IDataType> getInputParameters(String actionName)
	{
		return component.core().getInputParameters(actionName);
	}
	
	/**
	 * Returns the return type of the specified action.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @return the return data type of the specified action. 
	 */
	public static IDataType getReturnType(String actionName)
	{
		return component.core().getReturnType(actionName);
	}
	
	/**
	 * Evaluate the given (microflow)expression.
	 * @param context the context
	 * @param variables name of variables referenced in the expression (without '$') and their values.
	 * @param expression the expression
	 * @return the evaluated value of the expression.
	 */
	public static Object evaluateExpression(IContext context, Map<String,Object> variables, String expression)
	{
		return component.core().evaluateExpression(context, variables, expression);
	}
	
	/**
	 * Execute an action asynchronously, result is given and/or exceptions are raised when calling Future.get().
	 * When calling Future.get() the result of the action will return immediately if the execution is done, 
	 * otherwise the call is blocking. Exceptions raised while executing the action will not be thrown until 
	 * Future.get() is called.
	 * @param <T> action type, subclass of CoreAction.
	 * @param <R> result type of the action, can be any object. 
	 * @param action the action to execute.
	 * @return the Future object. 
	 * 
	 */
	public static <T extends CoreAction<R>,R> Future<R> execute(T action)
	{
		return component.core().execute(action);
	}
	
	/**
	 * Execute an action synchronously.
	 * @param <T> action type, subclass of CoreAction.
	 * @param <R> result type of action, can be any object. 
	 * @param action the action to execute.
	 * @return return value of the specified action.
	 */
	public static <T extends CoreAction<R>,R> R executeSync(T action) throws CoreException
	{
		return component.core().executeSync(action);
	}
	
	/**
	 * Execute the specified action (synchronously).
	 * @param context the context for this action.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @param params for microflows: add IMendixObject, IMendixIdentifier or primitive parameters.
	 * 		         for Java actions: add any object parameters.
	 * @return return value of the specified action.
	 */
	public static <R> R execute(IContext context, String actionName, Object ... params) throws CoreException
	{
		return (R)component.core().execute(context, actionName, params);
	}
	
	/**
	 * Execute the specified action (asynchronously). Use only to call other Java actions (not microflows)!
	 * When calling microflows use {@link #executeAsync(IContext, String, boolean, Map<String, Object>)}
	 * with a named parameter map instead.
	 * Result is given and/or exceptions are raised when calling Future.get().
	 * When calling Future.get() the result of the action will return immediately if the execution is done, 
	 * otherwise the call is blocking. Exceptions raised while executing the action will not be thrown until 
	 * Future.get() is called.
	 * @param context the context for this action.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @param params ordered params for the Java action.
	 * @return the Future object.
	 */
	public static <R> Future<R> executeAsync(IContext context, String actionName, Object ... params) throws CoreException
	{
		return component.core().executeAsync(context, actionName, params);
	}

   /**
   * Execute the specified microflow (asynchronously).
   *
   * @param context              the context for this microflow.
   * @param microflowName        the name of the microflow (format "ModuleName.ActionName").
   * @param executeInTransaction defines whether the microflow should be executed in a transaction (enables rolling back changes when exceptions are raised).
   * @param params               microflow parameters by name.
   * @return return value of the specified microflow.
   */
	public static <R> Future<R> executeAsync(
      IContext context,
      String microflowName,
      boolean executeInTransaction,
      Map<String, Object> params) throws CoreException
	{
		return component.core().executeAsync(context, microflowName, -1, executeInTransaction, params);
	}

	/**
	 * Execute the specified action (synchronously).
	 * @param context the context for this action.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @param params action parameters by name.
	 * @return return value of the specified action.
	 */
	public static <R> R execute(IContext context, String actionName, Map<String,Object> params) throws CoreException
	{
		return (R)component.core().execute(context, actionName, params);
	}
	
	/**
	 * Execute the specified action (synchronously).
	 * @param context the context for this action.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @param executeInTransaction defines whether the action should be execute in a transaction (enables rolling back changes when exceptions are raised).
	 * @param params action parameters by name.
	 * @return return value of the specified action.
	 */
	public static <R> R execute(IContext context, String actionName, boolean executeInTransaction, Map<String,Object> params) throws CoreException
	{
		return (R)component.core().execute(context, actionName, executeInTransaction, params);
	}
	
	/**
	 * Execute the specified action (asynchronously).
	 * @param <R> result type of the action, can be any object.
	 * @param <T> action type, subclass of CoreAction.
	 * @param action the action to execute.
	 */
	public static <T extends CoreAction<R>,R> void executeVoid(T action)
	{
		component.core().executeVoid(action);
	}
	
	/**
	 * Schedule an action on a certain date.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName")
	 * @param date the date and time on which the action should be executed
	 * @return the RunnableScheduledFuture object for keeping track of the result
	 */
	public static <R> RunnableScheduledFuture<?> schedule(String actionName, Date date) throws CoreException
	{
		return component.core().schedule(actionName, date);
	}
	
	/**
	 * Schedule an action on a certain delay from now.
	 * @param action the action to execute.
	 * @param delay	the delay after which the action should be executed.
	 * @param timeUnit time unit in which the delay is specified.
	 * @return returns RunnableScheduleFuture object for keeping track of the result.
	 */
	public static <R> RunnableScheduledFuture<?> schedule(CoreAction<R> action, long delay, TimeUnit timeUnit)
	{
		return component.core().schedule(action, delay, timeUnit);
	}
	
	/**
	 * Schedule a periodic action that runs for the first time after the given initial delay (first run),
	 * and subsequently with the given period; that is executions will commence after initialDelay then 
	 * initialDelay+period, then initialDelay + 2 * period, and so on.
	 * No result will be returned.
	 * @param actionName the name of a microflow or java action (format "ModuleName.ActionName").
	 * @param firstRun the date on which the action will be executed the first time.
	 * @param period the period between each start of the execution of the action.
	 * @param timeUnit the timeUnit in which the initialDelay and the period is specified.
	 * @param name the name of the scheduled event.
	 * @param description the description of the scheduled event.
	 */
	public static <R> void scheduleAtFixedRate(String actionName, Date firstRun, long period, TimeUnit timeUnit, String name, String description)
	{
		component.core().scheduleAtFixedRate(actionName, firstRun, period, timeUnit, name, description);
	}
	
	/**
	 * Schedule a periodic action that runs for the first time after the given initial delay,
	 * and subsequently with the given period; that is executions will commence after initialDelay then 
	 * initialDelay+period, then initialDelay + 2 * period, and so on.
	 * No result will be returned.
	 * @param action the action to execute.
	 * @param initialDelay the delay after which the action will be executed the first time.
	 * @param period the period between each start of the execution of the action.
	 * @param timeUnit the timeUnit in which the initialDelay and the period is specified.
	 */
	public static <R> void scheduleAtFixedRate(CoreAction<R> action, long initialDelay, long period, TimeUnit timeUnit)
	{
		component.core().scheduleAtFixedRate(action, initialDelay, period, timeUnit);
	}
	
	/**
	 * Schedule a periodic action that run for the first time on the given date/time,
	 * and subsequently with the given period; that is executions will commence on firstRun then 
	 * initialDelay+period, then initialDelay + 2 * period, and so on.<br>
	 * No result will be returned.
	 * @param action the action to execute
	 * @param firstRun the Date/time on which the action will be executed the first time
	 * @param period the period between each start of the execution of the action
	 * @param timeUnit the timeUnit in which the period is specified
	 */
	public static <R> void scheduleAtFixedRate(CoreAction<R> action, Date firstRun, long period, TimeUnit timeUnit)
	{
		component.core().scheduleAtFixedRate(action, firstRun, period, timeUnit);
	}
	
	/**
	 * Schedule a periodic action that runs for the first time after the given initial delay, 
	 * and subsequently with the given delay between the termination of one execution and the commencement of the next.
	 * No result will be returned.
	 * @param action the action to execute.
	 * @param initialDelay the delay after which the action will be executed the first time.
	 * @param delay the delay between the end of the execution of the action and the start of the next time the action will be executed. 
	 * @param timeUnit the timeUnit in which the initialDelay and the delay is specified.
	 */
	public static <R> void scheduleWithFixedDelay(CoreAction<R> action, long initialDelay, long delay, TimeUnit timeUnit)
	{
		component.core().scheduleWithFixedDelay(action, initialDelay, delay, timeUnit);
	}
	
	/**
	 * Remove scheduled future.
	 * @param scheduledFuture the RunnableScheduledFuture to remove.
	 * @return whether removing the RunnableScheduledFuture was successful.
	 */
	public static boolean removeScheduledFuture(RunnableScheduledFuture<?> scheduledFuture)
	{
		return component.core().removeScheduledFuture(scheduledFuture);
	}
	
	/**
	 * Reschedule an action with a new delay.
	 * @param scheduledFuture the scheduledFuture (old action) to remove from the queue.
	 * @param action the action to reschedule.
	 * @param newDelay the new delay.
	 * @param timeUnit time unit of the delay.
	 * @return scheduled future object.
	 */
	public static <R> ScheduledFuture<?> reschedule(RunnableScheduledFuture<R> scheduledFuture, CoreAction<R> action, long newDelay, TimeUnit timeUnit)
	{
		return component.core().reschedule(scheduledFuture, action, newDelay, timeUnit);
	}
	
	/**
	 * Registers the given ActionListener to the ActionManager.
	 * @param al the ActionListener to add.
	 */
	public static <T extends CoreAction<?>> void addListener(ActionListener<T> al)
	{
		component.core().addListener(al);
	}
	
	/**
	 * Commits the given object (asynchronously). This will store the object in the database and remove it from the server cache.
	 * This action is not executed in a transaction.
	 * @param context the context.
	 * @param object the IMendixObject to commit.
	 * @return returns a list of future objects with committed object lists, one for each entity type.
	 */
	public static List<Future<List<IMendixObject>>> commitAsync(IContext context, List<IMendixObject> objects)
	{
		return component.core().commitAsync(context, objects);
	}
	
	/**
	 * Commits the given object. This will store the object in the database and remove it from the server cache.
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param object the IMendixObject to commit.
	 * @return returns committed object if commit was successful, otherwise null.
	 */
	public static IMendixObject commit(IContext context, IMendixObject object) throws CoreException
	{
		return component.core().commit(context, object);
	}
	
	/**
	 * Commits the given objects. This will store the objects in the database and remove them from the server cache.
	 * Before events defined for these objects are executed before any object will be committed and after events will be
	 * executed after all objects have been committed.
	 * This action is executed in a transaction. 
	 * @param context the context.
	 * @param objectsToCommit the objects to commit.
	 * @return returns committed objects if commits was successful, otherwise an empty list.
	 */
	public static List<IMendixObject> commit(IContext context, List<IMendixObject> objects)
	{
		return component.core().commit(context, objects);
	}
	
	/**
	 * Commits the given object without events. This will store the object in the database and remove it from the server cache.
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param object the IMendixObject to commit.
	 * @return returns committed object if commit was successful, otherwise null.
	 */
	public static IMendixObject commitWithoutEvents(IContext context, IMendixObject object)
	{
		return component.core().commitWithoutEvents(context, object);
	}
	
	/**
	 * Commit the given objects without events. This will store the objects in the database and remove it from the server cache.
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param objects the objects to commit.
	 * @return returns true if commit was successful, otherwise false.
	 */
	public static List<IMendixObject> commitWithoutEvents(IContext context, List<IMendixObject> objects)
	{
		return component.core().commitWithoutEvents(context, objects);
	}
		
	/**
	 * Creates a new IMendixObject with the given object type (asynchronously). The object will NOT be stored in the 
	 * database. This action is not executed in a transaction.
	 * @param context the context.
	 * @param objectType type of object to create (e.g. "System.User").
	 * @return returns the Future object.
	 */
	public static Future<IMendixObject> instantiateAsync(IContext context, String objectType)
	{
		return component.core().instantiateAsync(context, objectType);
	}
		
	/**
	 * Creates a new IMendixObject with the given object type (synchronously). The object will NOT be stored in the 
	 * database. This action is executed in a transaction.
	 * @param context the context.
	 * @param objectType type of object to create (e.g. "System.User"). 
	 * @return returns the newly created object.
	 */
	public static IMendixObject instantiate(IContext context, String objectType)
	{
		return component.core().instantiate(context, objectType);
	}
	
	/**
	 * Changes the given object in cache (synchronously). When the object is not cache yet, 
	 * the object will be retrieved from the database and put in the cache.
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param object the object to change.
	 * @param changes contains changes by member name (e.g. <"Name", "User1">).
	 * @return returns whether the change succeeded or not.
	 */
	public static boolean change(IContext context, IMendixObject object, Map<String,String> changes) throws CoreException
	{
		return component.core().change(context, object, changes);
	}
	
	/**
	 * Changes the object (asynchronously). Object will be stored in cache.
	 * This action is not executed in a transaction.
	 * @param context the context.
	 * @param obj the MendixObject to change
	 * @param changes contains changes by member name (e.g. <"Name", "User1">).
	 * @return returns the Future object.
	 */
	public static Future<Boolean> changeAsync(IContext context, IMendixObject obj, Map<String,String> changes)
	{
		return component.core().changeAsync(context, obj, changes);
	}
	
	/**
	 * Rollback changes of the object with the given id (asynchronously).
	 * When the object's state is NORMAL: Removes the object from the cache, all performed changes without commit will be lost.
	 * When the object's state is NEW: Removes the object from the database.
	 * This action is not executed in a transaction.
	 * @param context the context.
	 * @param id the identifier of the object to rollback.
	 * @return returns the Future object.
	 */
	public static Future<IMendixObject> rollbackAsync(IContext context, IMendixObject object)
	{
		return component.core().rollbackAsync(context, object);
	}
	
	/**
	 * Rollback changes of the object with the given id (synchronously).
	 * When the object's state is NORMAL: Removes the object from the cache, all performed changes without commit will be lost.
	 * When the object's state is NEW: Removes the object from the database.
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param id the identifier of the object to rollback.
	 * @return returns the Future object.
	 */
	public static IMendixObject rollback(IContext context, IMendixObject object) throws CoreException
	{
		return component.core().rollback(context, object);
	}
	
	/**
	 * Deletes the given objects from the database and server cache (synchronously).
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param objects the objects to delete.
	 * @return returns whether the delete succeeded.
	 */
	public static boolean delete(IContext context, IMendixObject... objects)
	{
		return component.core().delete(context, objects);
	}
	
	/**
	 * Deletes the given objects from the database and server cache (synchronously).
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param objectList the objects to delete.
	 * @return returns whether the delete succeeded. 
	 */
	public static boolean delete(IContext context, List<IMendixObject> objectList)
	{
		return component.core().delete(context, objectList);
	}
	
	/**
	 * Deletes the given objects from the database and server cache (synchronously) without events
	 * This action is executed in a transaction.
	 * @param context the context.
	 * @param objects the objects to delete.
	 * @return returns whether the delete succeeded.
	 */
	public static boolean deleteWithoutEvents(IContext context, List<IMendixObject> objects, boolean useDeleteBehavior)
	{
		return component.core().deleteWithoutEvents(context, objects, useDeleteBehavior);
	}
	
	/**
	 * Deletes the given object from the database and server cache (asynchronously).
	 * This action is not executed in a transaction.
	 * @param context the context.
	 * @param object the object to delete.
	 * @param useDeleteBehavior whether to use delete behavior.
	 * @return returns a list of future booleans, one for each entity type.
	 */
	public static List<Future<Boolean>> deleteAsync(IContext context, IMendixObject object, boolean useDeleteBehavior)
	{
		return component.core().deleteAsync(context, object, useDeleteBehavior);
	}
	
	/**
	 * Deletes the given object from the database and server cache (asynchronously).
	 * This action is not executed in a transaction.
	 * @param context the context.
	 * @param object the object to delete.
	 * @param useDeleteBehavior whether to use delete behavior.
	 * @return returns a list of future booleans, one for each entity type.
	 */
	public static List<Future<Boolean>> deleteAsync(IContext context, List<IMendixObject> objects, boolean useDeleteBehavior)
	{
		return component.core().deleteAsync(context, objects, useDeleteBehavior);
	}
	
	/**
	 * Retrieves objects with the given ids (asynchronously). First, objects are attempted to be retrieved from cache.
	 * When an object cannot be retrieve from the cache it will be retrieved from the database.
	 * When (amount > 0) || (offset > 0) || (sort.size() > 0), all objects will be retrieve from the database.
	 * @param context the context.
	 * @param ids ids of the objects to retrieve.
	 * @return returns the Future object.
	 */
	public static Future<List<IMendixObject>> retrieveIdListAsync(IContext context, List<IMendixIdentifier> ids)
	{
		return component.core().retrieveIdListAsync(context, ids);
	}
	
	/**
	 * Retrieves objects with the given ids (synchronously). First, objects are attempted to be retrieved from cache.
	 * When an object cannot be retrieve from the cache it will be retrieved from the database.
	 * When (amount > 0) || (offset > 0) || (sort.size() > 0), all objects will be retrieve from the database. 
	 * @param context the context.
	 * @param ids ids of the objects to retrieve.
	 * @param amount the maximum number of objects to retrieve from the database.
	 * @param offset offset of returned objects when retrieved from the database.
	 * @param sort sorting of returned objects when retrieved from the database (e.g. <"Name", "ASC">, <"Age", "DESC">).
	 * @return returns the Future object.
	 */
	public static List<IMendixObject> retrieveIdList(IContext context, List<IMendixIdentifier> ids, int amount, int offset, Map<String,String> sort) throws CoreException
	{
		return component.core().retrieveIdList(context, ids, amount, offset, sort);
	}
	
	/**
	 * Retrieves objects with the given ids (synchronously). First, objects are attempted to be retrieved from cache.
	 * When an object cannot be retrieved from the cache it will be retrieved from the database.
	 * When (amount > 0) || (offset > 0) || (sort.size() > 0), all objects will be retrieved from the database.
	 * @param context the context.
	 * @param ids ids of the objects to retrieve.
	 * @return returns the objects of the given ids.
	 */
	public static List<IMendixObject> retrieveIdList(IContext context, List<IMendixIdentifier> ids) throws CoreException
	{
		return component.core().retrieveIdList(context, ids);
	}
	
	/**
	 * Retrieves object with the given id (asynchronously). First, the object is attempted to be retrieved from the cache.
	 * When the object cannot be retrieve from the cache it will be retrieved from the database.
	 * @param context the context.
	 * @param id id of the object to retrieve.
	 * @return returns the Future object.
	 */
	public static Future<IMendixObject> retrieveIdAsync(IContext context, IMendixIdentifier id)
	{
		return component.core().retrieveIdAsync(context, id);
	}
	
	/**
	 * Retrieves object with the given id (synchronously). First, the object is attempted to be retrieved from the cache.
	 * When the object cannot be retrieve from the cache it will be retrieved from the database.
	 * @param context the context.
	 * @param id id of the object to retrieve.
	 * @return returns the Future object.
	 */
	public static IMendixObject retrieveId(IContext context, IMendixIdentifier id) throws CoreException
	{
		return component.core().retrieveId(context, id);
	}
	
	/**
	 * Retrieves objects using the given object and path.
	 * 
	 * @param context the context.
	 * @param mxObject the start point of the path.
	 * @param path the path (association) to the objects to retrieve.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveByPath(IContext context, IMendixObject mxObject, String path)
	{
		return component.core().retrieveByPath(context, mxObject, path);
	}
	
	/**
	 * Retrieves objects using the given object and path.
	 * 
	 * @param context the context.
	 * @param mxObject the start point of the path.
	 * @param path the path (association) to the objects to retrieve.
	 * @param isSelfAssociationChild defines whether the mxObject instance is the child of the path of a self association.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveByPath(IContext context, IMendixObject mxObject, String path, boolean isSelfAssociationChild)
	{
		return component.core().retrieveByPath(context, mxObject, path, isSelfAssociationChild);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (asynchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @param sort sorting of returned objects when retrieved from the database (e.g. <"Name", "ASC">, <"Age", "DESC">).
	 * @param depth	indicates the level until which each reference (IMendixIdentifier) is also retrieved as an IMendixObject.
	 * @return the Future object.
	 */
	public static Future<List<IMendixObject>> retrieveXPathQueryAsync(IContext context, String xpathQuery, int amount, int offset, Map<String,String> sort, int depth)
	{
		return component.core().retrieveXPathQueryAsync(context, xpathQuery, amount, offset, sort, depth);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @param sort sorting of returned objects when retrieved from the database (e.g. <"Name", "ASC">, <"Age", "DESC">).
	 * @param depth	indicates the level until which each reference (IMendixIdentifier) is also retrieved as an IMendixObject.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathQuery(IContext context, String xpathQuery, int amount, int offset, Map<String,String> sort, int depth) throws CoreException
	{
		return component.core().retrieveXPathQuery(context, xpathQuery, amount, offset, sort, depth);
	}
	
	/**
	 * Retrieves raw data (IDataTables) based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @param sort sorting of returned objects when retrieved from the database (e.g. <"Name", "ASC">, <"Age", "DESC">).
	 * @param depth	indicates the level until which each reference (IMendixIdentifier) is also retrieved as an IMendixObject.
	 * @return the data table containing the raw data.
	 */
	public static IDataTable retrieveXPathQueryRaw(IContext context, String xpathQuery, int amount, int offset, Map<String,String> sort, int depth) throws CoreException
	{
		return component.core().retrieveXPathQueryRaw(context, xpathQuery, amount, offset, sort, depth);
	}
	
	/**
	 * Retrieves raw data (IDataTables) based on the XPath query and given schema (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param shouldRetrieveCount indicates whether the total number object corresponding to the given schema should be included in the result.
	 * @param retrievalSchema the schema to apply.
	 * @return the data table containing the raw data.
	 */
	public static IDataTable retrieveXPathSchemaRaw(IContext context, String xpathQuery, boolean shouldRetrieveCount, IRetrievalSchema retrievalSchema) throws CoreException
	{
		return component.core().retrieveXPathSchemaRaw(context, xpathQuery, shouldRetrieveCount, retrievalSchema);
	}
	
	/**
	 * Retrieves objects based on the XPath query and given schema (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param retrievalSchema the schema to apply.
	 * @param shouldRetrieveCount indicates whether the total number object corresponding to the given schema should be included in the result. 
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathSchema(IContext context, String xpathQuery, IRetrievalSchema retrievalSchema, boolean shouldRetrieveCount) throws CoreException
	{
		return component.core().retrieveXPathSchema(context, xpathQuery, retrievalSchema, shouldRetrieveCount, false);
	}
	
	/**
	 * Retrieves objects based on the XPath query and given schema (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param retrievalSchema the schema to apply.
	 * @param shouldRetrieveCount indicates whether the total number object corresponding to the given schema should be included in the result.
	 * @param disableSecurity indicates whether security should be applied when this query is being executed.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathSchema(IContext context, String xpathQuery, IRetrievalSchema retrievalSchema, boolean shouldRetrieveCount, boolean disableSecurity) throws CoreException
	{
		return component.core().retrieveXPathSchema(context, xpathQuery, retrievalSchema, shouldRetrieveCount, disableSecurity);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @param sort sorting of returned objects when retrieved from the database (e.g. <"Name", "ASC">, <"Age", "DESC">).
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathQuery(IContext context, String xpathQuery, int amount, int offset, Map<String, String> sort) throws CoreException
	{
		return Core.retrieveXPathQuery(context, xpathQuery, amount, offset, sort, 0);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @param depth	indicates the level until which each reference (IMendixIdentifier) is also retrieved as an IMendixObject.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathQuery(IContext context, String xpathQuery, int depth) throws CoreException
	{
		return Core.retrieveXPathQuery(context, xpathQuery, -1, -1, new LinkedHashMap<String,String>(), depth);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathQuery the XPath query to execute.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathQuery(IContext context, String xpathQuery) throws CoreException
	{
		return Core.retrieveXPathQuery(context, xpathQuery, -1, -1, new LinkedHashMap<String,String>(), 0);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathFormat the XPath query to execute with %s for each param to escape.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @param sort sorting of returned objects when retrieved from the database (e.g. <"Name", "ASC">, <"Age", "DESC">).
	 * @param depth depth of the retrieval (0 is all attributes and association guids, 1 is also all attributes of 1-deep associations and 2-deep associaton guids etc.).
	 * @param params xpath arguments.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathQueryEscaped(IContext context, String xpathFormat, int amount, int offset, Map<String,String> sort, int depth, String... params) throws CoreException
	{
		return component.core().retrieveXPathQueryEscaped(context, xpathFormat, amount, offset, sort, depth, params);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathFormat the XPath query to execute with %s for each param to escape.
	 * @param retrievalSchema the schema to apply.
	 * @param shouldRetrieveCount indicates whether the total number object corresponding to the given schema should be included in the result.
	 * @param params a collection of parameters for each %s in xpathFormat.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathSchemaEscaped(IContext context, String xpathFormat, IRetrievalSchema retrievalSchema, boolean shouldRetrieveCount, String... params) throws CoreException
	{
		return Core.retrieveXPathSchemaEscaped(context, xpathFormat, retrievalSchema, shouldRetrieveCount, false, params);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathFormat the XPath query to execute with %s for each param to escape.
	 * @param retrievalSchema the schema to apply.
	 * @param shouldRetrieveCount indicates whether the total number object corresponding to the given schema should be included in the result.
	 * @param disableSecurity indicates whether security should be disabled when executing this query.
	 * @param params a collection of parameters for each %s in xpathFormat.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathSchemaEscaped(IContext context, String xpathFormat, IRetrievalSchema retrievalSchema, boolean shouldRetrieveCount, boolean disableSecurity, String... params) throws CoreException
	{
		return component.core().retrieveXPathSchemaEscaped(context, xpathFormat, retrievalSchema, shouldRetrieveCount, disableSecurity, params);
	}
	
	/**
	 * Retrieves object list based on the given XPath query (synchronously).
	 * @param context the context.
	 * @param xpathFormat the XPath query to execute with %s for each param to escape.
	 * @param params a collection of parameters for each %s in xpathFormat.
	 * @return the list of retrieved objects.
	 */
	public static List<IMendixObject> retrieveXPathQueryEscaped(IContext context, String xpathFormat, String... params) throws CoreException
	{
		return Core.retrieveXPathQueryEscaped(context, xpathFormat, -1, -1, new LinkedHashMap<String,String>(), 0, params);
	}
			
	/**
	 * Create a new IRetrievalSchema.
	 * @return an IRetrievalSchema.
	 */
	public static IRetrievalSchema createRetrievalSchema()
	{
		return component.core().createRetrievalSchema();
	}
	
	/**
	 * Create a new IMetaAssociationSchema to specify which associations must be retrieved.
	 * An IMetaAssociationSchema can be added to a request by the method IRetrievalSchema.addMetaAssociationSchema(..).
	 * 
	 * @param metaAssociationName the name of the meta association of this schema
	 * @param retrievalSchema the retrieval schema of the associated meta object
	 * @return an IMetaAssociationSchema
	 */
	public static IMetaAssociationSchema createMetaAssociationSchema(String metaAssociationName, IRetrievalSchema retrievalSchema) {
		return component.core().createMetaAssociationSchema(metaAssociationName, retrievalSchema);
	}
	
	/**
	 * Create a new IXPathTextGetRequest. This class can be used to define an XPath retrieval query. The query must be set in textual form.
	 * @return an IXPathTextGetRequest.
	 */
	public static IXPathTextGetRequest createXPathTextGetRequest() 
	{
		return component.core().createXPathTextGetRequest();
	}
	
	/**
	 * Create a new IOQLTextGetRequest. This class can be used to define a textual OQL retrieval query.
	 * @return an IOQLTextGetRequest.
	 */
	public static IOQLTextGetRequest createOQLTextGetRequest() 
	{
		return component.core().createOQLTextGetRequest();
	}
	
	/**
	 * Retrieve raw data (IDataTable) using an OQL query (asynchronously).
	 * @param context the context.
	 * @param oqlQuery the OQL query to execute.
	 * @return the Future object.
	 */
	public static Future<IDataTable> retrieveOQLDataTableAsync(IContext context, String oqlQuery)
	{
		return retrieveOQLDataTableAsync(context, oqlQuery, -1, -1);
	}
	
	/**
	 * Retrieve raw data (IDataTable) using an IGetRequest object (asynchronously).
	 * @param context the context.
	 * @param request the request object.
	 * @return the Future object.
	 */
	public static Future<IDataTable> retrieveOQLDataTableAsync(IContext context, IGetRequest request)
	{
		return component.core().retrieveOQLDataTableAsync(context, request);
	}
	
	/**
	 * Retrieve raw data (IDataTable) using an OQL query (asynchronously).
	 * @param context the context.
	 * @param oqlQuery the OQL query to execute.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @return the Future object.
	 */
	public static Future<IDataTable> retrieveOQLDataTableAsync(IContext context, String oqlQuery, int amount, int offset) 
	{
		return component.core().retrieveOQLDataTableAsync(context, oqlQuery, amount, offset);
	}
	
	/**
	 * Retrieve raw data (IDataTable) using an IGetRequest object (synchronously).
	 * @param context the context.
	 * @param request the request object.
	 * @return the data table containing the raw data.
	 */
	public static IDataTable retrieveOQLDataTable(IContext context, IGetRequest request) throws CoreException 
	{
		return component.core().retrieveOQLDataTable(context, request);
	}
	
	/**
	 * Retrieve raw data (IDataTable) using an IGetRequest object (synchronously).
	 * @param context the context.
	 * @param oqlQuery the OQL query to execute.
	 * @return the data table containing the raw data.
	 */
	public static IDataTable retrieveOQLDataTable(IContext context, String oqlQuery) throws CoreException 
	{
		return retrieveOQLDataTable(context, oqlQuery, -1, -1);
	}
	
	/**
	 * Retrieve raw data (IDataTable) using an OQL query (asynchronously).
	 * @param context the context.
	 * @param oqlQuery the OQL query to execute.
	 * @param amount maximum number of objects to retrieve. 
	 * @param offset index of first object to retrieve.
	 * @return the data table containing the raw data.
	 */
	public static IDataTable retrieveOQLDataTable(IContext context, String oqlQuery, int amount, int offset) throws CoreException 
	{
		return component.core().retrieveOQLDataTable(context, oqlQuery, amount, offset);
	}
	
	/**
	 * Retrieves long aggregate value based on the given query (root element of the query should be an aggregate function) (asynchronously).
	 * @param context the context.
	 * @param xpathQuery the aggregate xpath query (e.g. "COUNT(//System.User)").
	 * @return the Future object.
	 */
	public static Future<Long> retrieveXPathQueryAggregateAsync(IContext context, String xpathQuery)
	{
		return component.core().retrieveXPathQueryAggregateAsync(context, xpathQuery);
	}
	
	/**
	 * Retrieves long aggregate value based on the given query (root element of the query should be an aggregate function) (synchronously).
	 * @param context the context.
	 * @param xpathQuery the aggregate xpath query (e.g. "COUNT(//System.User)").
	 * @return the Future object.
	 */
	public static Long retrieveXPathQueryAggregate(IContext context, String xpathQuery) throws CoreException
	{
		return component.core().retrieveXPathQueryAggregate(context, xpathQuery);
	}
	
	/**
	 * Retrieves long aggregate value based on the given query and schema (root element of the query should be an aggregate function) (asynchronously).
	 * @param context the context.
	 * @param xpathQuery the aggregate xpath query (e.g. "COUNT(//System.User)").
	 * @param retrievalSchema the schema.
	 * @return the aggregate value.
	 */
	public static Long retrieveXPathQueryAggregateSchema(IContext context, String xpathQuery, IRetrievalSchema retrievalSchema) throws CoreException
	{
		return component.core().retrieveXPathQueryAggregateSchema(context, xpathQuery, retrievalSchema);
	}
	
	/**
	 * Retrieves long aggregate value based on the given query and schema (root element of the query should be an aggregate function) (asynchronously).
	 * @param context the context.
	 * @param xpathQuery the aggregate xpath query (e.g. "COUNT(//System.User)").
	 * @param retrievalSchema the schema.
	 * @param disableSecurity whether security should be applied for this retrieval.
	 * @return the aggregate value.
	 */
	public static Long retrieveXPathQueryAggregateSchema(IContext context, String xpathQuery, IRetrievalSchema retrievalSchema, boolean disableSecurity) throws CoreException
	{
		return component.core().retrieveXPathQueryAggregateSchema(context, xpathQuery, retrievalSchema, disableSecurity);
	}
	
	/**
	 * Retrieves long value based on the given query (query should have an aggregate function as root element)
	 * @param context
	 * @param xpathQuery
	 * @return returns Future object for action control and return of action result
	 */
	public static Future<Double> retrieveXPathQueryAggregateAsyncDouble(IContext context, String xpathQuery)
	{
		return component.core().retrieveXPathQueryAggregateAsyncDouble(context, xpathQuery);
	}
	
	/**
	 * Retrieves double aggregate value based on the given query (root element of the query should be an aggregate function) (synchronously).
	 * @param context the context.
	 * @param xpathQuery the aggregate xpath query (e.g. "COUNT(//System.User)").
	 * @return the Future object.
	 */
	public static Double retrieveXPathQueryAggregateDouble(IContext context, String xpathQuery) throws CoreException
	{
		return component.core().retrieveXPathQueryAggregateDouble(context, xpathQuery);
	}
	
	/**
	 * Returns contents of a file document as an input stream.
	 * @param context the context.
	 * @param fileDocument the file document from which the contents will be returned.
	 * @return the input stream of the file content of the given file document.
	  */
	public static InputStream getFileDocumentContent(IContext context, IMendixObject fileDocument)
	{
		return component.core().getFileDocumentContent(context, fileDocument);
	}
	
	/**
	 * Physically stores a file using the given input stream and commits the file document.
	 * @param context the context.
	 * @param fileDocument the file document to which the file to store is linked to.
	 * @param fileName the original name of the file (will be stored in the Name attribute)
	 * @param inputStream the content of the file
	 */
	public static void storeFileDocumentContent(IContext context, IMendixObject fileDocument, String fileName, InputStream inputStream)
	{
		component.core().storeFileDocumentContent(context, fileDocument, fileName, inputStream);
	}

	/**
	 * Physically stores a file using the given input stream and commits the file document.
	 * @param context the context.
	 * @param fileDocument the file document to which the file to store is linked to.
	 * @param inputStream the content of the file
	 */
	public static void storeFileDocumentContent(IContext context, IMendixObject fileDocument, InputStream inputStream)
	{
		component.core().storeFileDocumentContent(context, fileDocument, inputStream);
	}
	
	/**
	 * Physically stores an image using the given input stream and commits the image document.
	 * @param context the context.
	 * @param imageDocument the image document to which the image to store is linked to.
	 * @param inputStream the content of the file
	 * @param thumbnailWidth the width of the thumbnail to create for this image.
	 * @param thumbnailHeight the width of the thumbnail to create for this image.
	 */
	public static void storeImageDocumentContent(IContext context, IMendixObject imageDocument, InputStream inputStream, int thumbnailWidth, int thumbnailHeight) 
	{
		component.core().storeImageDocumentContent(context, imageDocument, inputStream, thumbnailWidth, thumbnailHeight);
	}
	
	/**
	 * Retrieve contents of the given image document.
	 * @param context the context.
	 * @param imageDocument the image document for which its contents are retrieved.
	 * @param retrieveThumbnail indicates whether a thumbnail or the full image is retrieved.
	 * @return the image as an input stream.
	 */
	public static InputStream getImage(IContext context, IMendixObject imageDocument, boolean retrieveThumbnail)
	{
		return component.core().getImage(context, imageDocument, retrieveThumbnail);
	}
	
	/**
	 * Checks whether a type is a subclass of or equal to a potential super class.
	 * @param superClass the name of the super class
	 * @param type the name of the type to check
	 * @return returns true if type is a subclass of superClass or if type equals superClass
	 */
	public static boolean isSubClassOf(String superClass, String type)
	{
		return component.core().isSubClassOf(superClass, type);
	}
	
	/**
	 * Checks whether a type is a subclass of or equal to a potential super class.
	 * @param superObject the super object.
	 * @param type the name of the type to check.
	 * @return returns true if type is a subclass of superClass or if type equals superClass.
	 */
	public static boolean isSubClassOf(IMetaObject superObject, IMetaObject type)
	{
		return component.core().isSubClassOf(superObject, type);
	}
	
	/**
	 * Checks whether a type is a subclass of or equal to a potential super class.
	 * @param superClass the name of the super class
	 * @param typeHash the hash of the name of the type to check
	 * @return returns true if type is a subclass of superClass or if type equals superClass
	 * @throws CoreException 
	 */
	public static boolean isSubClassOf(String superClass, short typeHash)
	{
		return component.core().isSubClassOf(superClass, typeHash);
	}
	
	/**
	 * Get all IMendixObject types.
	 * @return returns a list with all IMendixObjects (one object for each existing type)
	 */
	public static Iterable<IMendixObject> getAllMendixObjects()
	{
		return component.core().getAllMendixObjects();
	}
	
	/**
	 * Get all subtypes for an object type (including subtypes of subtypes, etc.). 
	 * @param objectType the object type.
	 * @return list of subtypes, in no particular order.
	 */
	public static List<String> getSubtypesOf(String objectType) 
	{
		return component.core().getSubtypesOf(objectType);
	}

	/**
	 * Get the IMetaObject corresponding to the given meta object name.
	 * @param metaObjectName the meta object name.
	 * @return returns the IMetaObject for the given metaObjectName
	 */
	public static IMetaObject getMetaObject(String metaObjectName)
	{
		return component.core().getMetaObject(metaObjectName);
	}
	
	/**
	 * Get the IMetaPrimtive based on a qualified attribute name (e.g. "System.User.Name").
	 * @param qualifiedAttributeName the qualified attribute name.
	 * @return the IMetaPrimtive.
	 */
	public static IMetaPrimitive getMetaPrimitive(String qualifiedAttributeName)
	{
		return component.core().getMetaPrimitive(qualifiedAttributeName);
	}
	
	/**
	 * Get all IMetaObjects.
	 * @return returns all IMetaObjects.
	 */
	public static Iterable<IMetaObject> getMetaObjects() 
	{
		return component.core().getMetaObjects();
	}
	
	/**
	 * Get all IMetaAssociations.
	 * @return returns all IMetaAssociations.
	 */
	public static Iterable<IMetaAssociation> getMetaAssociations() 
	{
		return component.core().getMetaAssociations();
	}
	
	/**
	 * Get the IMetaAssociation corresponding to the given association name.
	 * @param association the association name (e.g. "System.UserRoles").
	 * @return returns the IMetaAssociation for the given association name.
	 */
	public static IMetaAssociation getMetaAssociation(String association) 
	{
		return component.core().getMetaAssociation(association);
	}

	/**
	 * @param iMetaObject the meta object to get the database table name for
	 * @return the name of the database table
	 */
	public static String getDatabaseTableName(IMetaObject iMetaObject) {
		return component.core().getDatabaseTableName(iMetaObject);
	}
	
	/**
	 * @param iMetaAssociation the meta association to get the database table name for
	 * @return the name of the database table
	 */
	public static String getDatabaseTableName(IMetaAssociation iMetaAssociation) {
		return component.core().getDatabaseTableName(iMetaAssociation);
	}
	
	/**
	 * @param iMetaPrimitive the meta primitive to get the database column name for
	 * @return the name of the database column
	 */
	public static String getDatabaseColumnName(IMetaPrimitive iMetaPrimitive) {
		return component.core().getDatabaseColumnName(iMetaPrimitive);
	}
	
	/**
	 * @param iMetaAssociation the meta association to get the database child column name for
	 * @return the name of the database child column name
	 */
	public static String getDatabaseChildColumnName(IMetaAssociation iMetaAssociation) {
		return component.core().getDatabaseChildColumnName(iMetaAssociation);
	}
	
	/**
	 * @param iMetaAssociation the meta association to get the database parent column name for
	 * @return the name of the database parent column name
	 */
	public static String getDatabaseParentColumnName(IMetaAssociation iMetaAssociation) {
		return component.core().getDatabaseParentColumnName(iMetaAssociation);
	}
	
	/**
	 * Returns the context of the system session (this is always a sudo context).
	 * The system session has no associated user or user roles.
	 * @return returns the system session context. 
	 */
	public static IContext createSystemContext()
	{
		return component.core().createSystemContext();
	}
	
	/**
	 * Creates a IMendixIdentifier for the given guid.
	 * @param guid the guid.
	 * @return returns the created MendixIdentifier.
	 */
	public static IMendixIdentifier createMendixIdentifier(String guid)
	{
		return component.core().createMendixIdentifier(guid);
	}
	
	/**
	 * Creates a new IMendixIdentifier for the given guid.
	 * @param guid the guid.
	 * @return returns the created MendixIdentifier.
	 */
	public static IMendixIdentifier createMendixIdentifier(long guid)
	{
		return component.core().createMendixIdentifier(guid);
	}
	
	/**
	 * Authenticate the given user with the given password.
	 * @param context
	 * @param user the user.
	 * @param password the password.
	 * @return returns true if authentication was successful.
	 */
	public static boolean authenticate(IContext context, IUser user, String password) throws CoreException
	{
		return component.core().authenticate(context, user, password);		
	}
	
	/**
	 * Generic login method (can be used in modules in combination with LoginAction replacement).
	 * @param params the params.
	 * @return the created session if login was successful.
	 */
	public static ISession login(Map<String,? extends Object> params) throws CoreException
	{
		return component.core().login(params);
	}
	
	/**
	 * Login user with the given user name and password.
	 * @param userName the user name.
	 * @param password the password.
	 * @return the created session if login is successful.
	 */
	public static ISession login(String userName, String password) throws CoreException
	{
		return component.core().login(userName, password);
	}
	
	/**
	 * Login user with the given parameters.
	 * @param userName the user name.
	 * @param password the password.
	 * @param currentSessionId current session UUID.
	 * @return the created session if login is successful.
	 */
	public static ISession login(String userName, String password, String currentSessionId) throws CoreException
	{
		return component.core().login(userName, password, currentSessionId);
	}

	public static ISession login(String userName, String password, IMxRuntimeRequest request) throws CoreException
	{
		return component.core().login(userName, password, request);
	}
	
	/**
	 * Logout the given session. When the session is persistent it will be removed from the database.
	 * If the session is not persistent it will removed from the session cache.
	 * @param session the session to logout.
	 */
	public static void logout(ISession session)
	{
		component.core().logout(session);
	}
	
	/**
	 * Returns a user using the given user name.
	 * @param context the context.
	 * @param userName the user name to retrieve a user for.
	 * @return the retrieved user.
	 */
	public static IUser getUser(IContext context, String userName) throws CoreException
	{
		return component.core().getUser(context, userName);
	}
	
	/**
	 * Initialize a new session for the given user.
	 * @param user the user for which the session should be initialized.
	 * @param currentSessionId id of the current session, will be used to transfer data when current session is associated with a guest user.
	 * @return the created session.
	 */
	public static ISession initializeSession(IUser user, String currentSessionId) throws CoreException
	{
		return component.core().initializeSession(user, currentSessionId);
	}
	
	/**
	 * Initialize a new session for a guest user
	 * @return the created session
	 * @throws CoreException
	 */
	public static ISession initializeGuestSession() throws CoreException
	{
		return component.core().initializeGuestSession();
	}

	/**
	 * Import an xml stream, map this stream to domain objects and store those object in the Mendix database.
	 * @param context the context.
	 * @param xmlStream the xml stream to map and store.
	 * @param importMappingName name of the mapping from xml to domain objects (as defined in the Mendix Modeler, e.g. "Orders.MyMapping").
	 * @param mappingParameter parameter object used during the mapping (optional)
	 * @param shouldValidate whether the xml should be validated.
	 */
	public static void importXmlStream(IContext context, InputStream xmlStream, String importMappingName, IMendixObject mappingParameter, boolean shouldValidate)
	{
		integration.importXmlStream(context, xmlStream, importMappingName, mappingParameter, shouldValidate);
	}

	/**
	 * Import an xml stream, map this stream to domain objects and store those object in the Mendix database.
	 * @param context the context.
	 * @param xmlStream the xml stream to map and store.
	 * @param importMappingName name of the mapping from xml to domain objects (as defined in the Mendix Modeler, e.g. "Orders.MyMapping").
	 * @param mappingParameter parameter object used during the mapping (optional)
	 * @param storeResultInVariable whether to store the result of the xml mapping in variable which will be returned by this method.
	 * @param hasListReturnValue indicates whether the return value of the xml mapping is of type List.
	 * @param shouldValidate whether the xml should be validated.
	 */
	public static Object importXmlStream(IContext context, InputStream xmlStream, String importMappingName, IMendixObject mappingParameter,
			boolean storeResultInVariable, boolean hasListReturnValue, boolean shouldValidate)
	{
		return integration.importXmlStream(context, xmlStream, importMappingName, mappingParameter, storeResultInVariable, -1, hasListReturnValue, shouldValidate);
	}
	
	/**
	 * Call a webservice
	 * Post method headers:
	 *  - Content-Type: text/xml
	 *  - Host: location host (e.g. www.w3schools.com)
	 *  - SoapAction: soapAction (e.g. http://tempuri.com/FahrenheitToCelsius)
	 * @param location the webservice location url
	 * @param soapAction the webservice soap action
	 * @param soapRequestMessage
	 * <pre>
	 * {@code
	 * <?xml version="1.0" encoding="utf-8"?>
	 *	<soap:envelope 
	 *			xmlns:xsd="http://www.w3.org/2001/XMLSchema" 
	 *			xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" 
	 *			xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	 *	>
	 *		<soap:header>
	 *			<authentication>
	 *				<username> username </username>
	 *				<password> password </password>
	 *			</authentication>
	 *		</soap:header>
     *		<soap:body>
     *   		<operationName xmlns:=targetNamespace>
     *       		<x>5</x>
     *       		<y>5.0</y>
     *   		</operationName>
     *		</soap:body>
	 * </soap:envelope>
	 * }
	 * </pre>
	 * @return a soap envelope response stream, e.g.:
	 * <pre>
	 * {@code
	 * <?xml version="1.0" encoding="utf-8"?>
	 * <soap:Envelope 
	 * 		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
	 * 		xmlns:xsd="http://www.w3.org/2001/XMLSchema" 
	 * 		xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
	 * >
  	 * 		<soap:Body>
     * 			<OperationResponse xmlns=namespace>
     * 				<Result>string</Result>
     * 			</OperationResponse>
     * 		</soap:Body>
	 *	</soap:Envelope>
	 *}
	 *</pre>
	 * @throws WebserviceException
	 * @throws IOException
	 * @deprecated Don't use this, it will be removed in the future. If you want to build your own custom web
	 * service calls, you can fully implement this yourself.
	 */
	@Deprecated   
	public static IWebserviceResponse callWebservice(String location, String soapAction, String soapRequestMessage) 
	{
		return integration.callWebservice(location, soapAction, soapRequestMessage);
	}
	
	/**
	 * Call a webservice
	 * Post method headers:
	 *  - Content-Type: text/xml
	 *  - Host: location host (e.g. www.w3schools.com)
	 *  - SoapAction: soapAction (e.g. http://tempuri.com/FahrenheitToCelsius)
	 * @param location the webservice location url
	 * @param soapAction the webservice soap action
	 * @param soapRequestMessage
	 * <pre>
	 * {@code 
	 * <?xml version="1.0" encoding="utf-8"?>
	 *	<soap:envelope 
	 *			xmlns:xsd="http://www.w3.org/2001/XMLSchema" 
	 *			xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" 
	 *			xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	 *	>
	 *		<soap:header>
	 *			<authentication>
	 *				<username> username </username>
	 *				<password> password </password>
	 *			</authentication>
	 *		</soap:header>
     *		<soap:body>
     *   		<operationName xmlns:=targetNamespace>
     *       		<x>5</x>
     *       		<y>5.0</y>
     *   		</operationName>
     *		</soap:body>
	 * </soap:envelope>
	 * }
	 * </pre>
	 * @return a soap envelope response stream, e.g.:
	 * <pre>
	 * {@code
	 * <?xml version="1.0" encoding="utf-8"?>
	 * <soap:Envelope 
	 * 		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
	 * 		xmlns:xsd="http://www.w3.org/2001/XMLSchema" 
	 * 		xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
	 * >
  	 * 		<soap:Body>
     * 			<OperationResponse xmlns=namespace>
     * 				<Result>string</Result>
     * 			</OperationResponse>
     * 		</soap:Body>
	 *	</soap:Envelope>
	 *}
	 *</pre>
	 * @throws WebserviceException
	 * @throws IOException
	 * @deprecated Don't use this, it will be removed in the future. If you want to build your own custom web
	 * service calls, you can fully implement this yourself.
	 */
	@Deprecated
	public static IWebserviceResponse callWebservice(String location, String soapAction, InputStream soapRequestMessage) 
	{
		return integration.callWebservice(location, soapAction, soapRequestMessage);
	}
	
	/**
	 * Add a custom request handler to the Mendix Business Server. This request
	 * handler will process MxRuntimeRequests on the given path. Responses should be
	 * given by adding information to the MxRuntimeResponse. 
	 * @param path the path for which request should be processed.
	 * @param requestHandler the custom request handler.
	 */
	public static void addRequestHandler(String path, RequestHandler requestHandler)
	{
		component.runtime().getConnector().addRequestHandler(path, requestHandler);
	}
	
	public static ILogNode getLogger(String name)
	{
		return component.core().getLogger(name);
	}
	
	/**
	 * Returns the current configuration.
	 * @return the configuration.
	 */
	public static Configuration getConfiguration()
	{
		return component.core().getConfiguration();
	}
	
	/**
	 * Resolve tokens in the given text.
	 * Possible tokens:
	 * - [%CurrentDateTime%]
	 * - [%CurrentUser%]
	 * - [%SecondLength%]
	 * - [%MinuteLength%]
	 * - [%HourLength%]
	 * - [%DayLength%]
	 * - [%WeekLength%]
	 * - [%MonthLength%]
	 * - [%YearLength%]
	 * - [%BeginOfCurrentMinute%]
	 * - [%EndOfCurrentMinute%]
	 * - [%BeginOfCurrentHour%]
	 * - [%BeginOfCurrentDay%]
	 * - [%EndOfCurrentDay%]
	 * - [%BeginOfCurrentWeek%]
	 * - [%EndOfCurrentWeek%]
	 * - [%BeginOfCurrentMonth%]
	 * - [%EndOfCurrentMonth%]
	 * - [%BeginOfCurrentYear%]
	 * - [%EndOfCurrentYear%]
	 * - [%UserRole_%Name%%] (e.g. [%UserRole_Administrator%]) 
	 * @param text the text to resolve.
	 * @param context the context.
	 * @return the resolved object.
	 */
	public static Object resolveTokens(IContext context, String text)
	{
		return component.core().resolveTokens(context, text);
	}
	
	/////////////// ActionManager statistics //////////////////////////////
	
	/** 
	 * Returns the current number of active actions. This number represents only the actions
	 * which were started asynchronously.
	 * @return number of current active actions.
	 */
	public static int getActiveActionCount()
	{
		return component.core().getActiveActionCount();
	}
	
	/**
	 * Returns the number of completed actions since server startup. This number represents
	 * only the actions which were started asynchronously.
	 * @return number of completed actions
	 */
	public static long getCompletedActionCount()
	{
		return component.core().getCompletedActionCount();
	}
	
	/**
	 * Returns the current action pool queue size.
	 * @return returns current queue size of action thread pool.
	 */
	public static int getActionQueueSize()
	{
		return component.core().getActionQueueSize();
	}
	
	/**
	 * Returns the current action pool size.
	 * @return the current size of the action thread pool.
	 */
	public static int getCurrentPoolSize()
	{
		return component.core().getCurrentPoolSize();
	}
	
	/**
	 * Returns the largest action pool size.
	 * @return the maximum number of threads the thread pool has ever ran.
	 */
	public static int getLargestPoolSize()
	{
		return component.core().getLargestPoolSize();
	}
	
	/**
	 * Returns the number of actions currently scheduled for future execution.
	 * @return the number of scheduled actions.
	 */
	public static int getScheduledActionCount()
	{
		return component.core().getScheduledActionCount();
	}
	
	/**
	 * Returns the maximum number of concurrent users since the server was started.
	 * @return maximum number of concurrent users.
	 */
	public static int getMaximumNumberConcurrentUsers() throws CoreException
	{
		return component.core().getMaximumNumberConcurrentUsers();
	}
	
	/**
	 * Returns current number of concurrent sessions.
	 */
	public static long getNumberConcurrentSessions()
	{
		return component.core().getNumberConcurrentSessions();
	}
	
	/**
	 * @return the active sessions.
	 */
	public static Collection<? extends ISession> getActiveSessions()
	{
		return component.core().getActiveSessions();
	}
	
	/**
	 * @param userName the user name associated with the session to search for.
	 * @return the session associated with the given user name if such a session exists, otherwise null.
	 */
	public static ISession getActiveSession(String userName)
	{
		return component.core().getActiveSession(userName);
	}
	
	public static long getNamedUserCount()
	{
		return component.core().getNamedUserCount();
	}
	
	/**
	 * The current number of concurrent users.
	 * @param anonymous whether anonymous users should be included in the count.
	 * @return the number of concurrent users.
	 */
	public static long getConcurrentUserCount(boolean anonymous)
	{
		return component.core().getConcurrentUserCount(anonymous);
	}
	
	/**
	 * Returns the translated string for a certain key and context. The context is used
	 * to retrieve the language of the current user.
	 * @param context the context.
	 * @param key the key referring to the translatable string.
	 * @param args the arguments which should be applied to translatable string template.
	 * @return the translated string.
	 */
	public static String getInternationalizedString(IContext context, String key, Object ...args)
	{
		return component.core().getInternationalizedString(context, key, args);
	}
	
	/**
	 * Returns the translated string for a certain key and language code.
	 * @param languageCode the language code (ISO-639).
	 * @param key the key referring to the translatable string.
	 * @param args values which should replace possible templates in the translatable string ({1}, {2}, etc.).
	 * @return the translated string.
	 */
	public static String getInternationalizedString(String languageCode, String key, Object ... args)
	{
		return component.core().getInternationalizedString(languageCode, key, args);
	}
	
	/**
	 * Returns the default language of the loaded project.
	 * @return the default langauge.
	 */
	public static ILanguage getDefaultLanguage()
	{
		return component.core().getDefaultLanguage();
	}
	
	/**
	 * Retrieve locale using the given context. 
	 * @param context the context.
	 * @return the Locale.
	 */
	public static Locale getLocale(IContext context)
	{
		return component.core().getLocale(context);
	}
	
	/**
	 * Retrieve locale using the given language code (e.g. en_US). 
	 * @param languageCode the languageCode (ISO-639).
	 * @return the Locale.
	 */
	public static Locale getLocale(String languageCode)
	{
		return component.core().getLocale(languageCode);
	}
		
	/**
	 * Returns the startup date time of the Mendix Business Server.
	 * @return the date time on which the Mendix Business Server was started.
	 */
	public static Date getStartupDateTime()
	{
		return component.core().getStartupDateTime();
	}

	public static void registerLogSubscriber(LogSubscriber subscriber)
	{
		component.core().registerLogSubscriber(subscriber);
	}
		
	/**
	 * Prints the message and stacktrace of a Throwable and its cause(s).
	 * @param trace the StringBuilder the exception is printed to.
	 * @param e the Throwable to print.
	 */
	public static void buildException(StringBuilder trace, Throwable e)
	{
		component.core().buildException(trace, e);
	}
		
	/**
	 * Add the action specified by the given action name to action registry. This enables calling
	 * <code>Core.execute(actionName)</code> for this action.
	 * @param actionName the fully qualified class name of the action (e.g. com.mendix.action.MyAction).
	 */
	public static void addUserAction(Class<? extends UserAction<?>> userActionClass) 
	{
		component.core().addUserAction(userActionClass);
	}

	/**
	 * Creates a DataType based on a type.<br>
	 * Possible types:<br>
	 * - Boolean <br>
	 * - Integer <br>
	 * - Long <br>
	 * - Float <br>
	 * - String <br>
	 * - Datetime <br>
	 * - {code#id} (enumeration key) <br>
	 * - ModuleName.ObjectName (IMendixObject)<br>
	 * - [ModuleName.ObjectName] (List<IMendixObject>) <br>
	 * @param type the type to base the IDataType on.
	 * @return the resulting IDataType.
	 */
	public static IDataType createDataType(String type)
	{		
		return component.core().createDataType(type);
	}

	/**
	 * Creates a DataType based on an object type and a attribute name
	 * @param objectType the object type (format: "ModuleName.EntityName").
	 * @param attributeName the attribute to create the IDataType for (should be a member of above object type)
	 * @return the resulting IDataType
	 */
	public static IDataType createDataType(String objectType, String attributeName)
	{
		return component.core().createDataType(objectType, attributeName);
	}

	public static IProfiler getProfiler() 
	{
		return component.core().getProfiler();
	}
	
	public static void registerProfiler(IProfiler profiler) throws MendixException 
	{
		component.core().registerProfiler(profiler);
	}
	
	public static void unregisterProfiler() 
	{
		component.core().unregisterProfiler();
	}
}

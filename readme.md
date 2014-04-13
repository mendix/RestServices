# Rest Services

![RestServices](images/logo.png) 

[GitHub](https://github.com/mweststrate/RestServices) - [Mendix Appstore](https://appstore.mendix.com/link/app/rest%20services)

Welcome to the Rest Services module. This module can be used in [Mendix](http://www.mendix.com/) apps as toolkit if you want to achieve one of the following three purposes:

1. Consume JSON REST based services
2. Publish data or microflows through REST API's
3. (Real time) Synchronization of data between Mendix applications

*Note: the RestServices module depends on the [Community Commons](https://appstore.mendix.com/link/app/community%20commons) module, version 4.3.2 or higher*

# Consuming REST services

This module is able to invoke most, if not any, REST service which is based on JSON, form-data or multipart data (that is; files). The operations in the 'Consume' folder of the module provide the necessary tools to invoke data. The work horse of all this operations is the java action `request`. Most other methods are wrappers around this operation. 

## The `Request` java action
Request performs an HTTP request and provides the means to both send data and receive data over HTTP. Its parameters are defined as follow. 

* `method`, the HTTP method to use, which is one of `GET`, `POST`, `PUT` or `DELETE`. Which method to use is usually defined in the documentation of the service to consume. Otherwise see the 'HTTP verbs in REST' section for more information. As a rule of thumb: If you only want to fetch data use `GET`, otherwise use `POST`.
* `url` defines the HTTP endpoint to which the request should be send. 
* `optRequestData` is an optional Mendix object that provides the parameters which are used in the request (note that it is also possible to pass parameters in the `url` itself). This object will be serialized to JSON (see the corresponding section) and be passed to the endpoint. The format in which the data will be send depends on the actual `method` and `sendWithFormEncoding` parameter. 
* `optResponseData` is an optional Mendix object in which the response data will be stored. This object will be filled by any JSON / file data which is in the response body of the request. See the 'JSON deserialization' section for more details how a JSON body is parsed into a Mendix object. 

This method returns a `RequestResult` object if the service responds with HTTP response `200 OK` or `304 Not Modified`. In all other cases an exception will be thrown. 

## The `RequestResult` object
Most REST operations return a `RequestResult` object which contains the meta information of a response. An instance contains the following fields:

* `ResponseCode` stating whether the server responded with 'OK'  or 'Not modified'. A 'Not modified' response might be send by the server if, for example, an 'If-none-modified' header was send, which indicates that you received the proper response to this request in an earlier request. See for example [ETags](http://en.wikipedia.org/wiki/HTTP_ETag)
* `RawResponseCode` idem, but as HTTP status code. Either '200' or '304'. 
* `ETag` if the response contained an `ETag` header, it is picked up and stored in this field. It can be used as optimization for any subsequent requests. 
* `ResponseBody` the full and raw response body of the request. This field is only set if the body of the response is not yet parsed (by providing an `optResponseData` parameter for example). 

## Sending request headers using `addHeaderToNextRequest`
Many REST services require the usage of custom request handlers for authentication, caching etc. In the RestServices module, any call to `addHeaderToNextRequest` will add a header to the *next* (and only the next) request that will be made by the current microflow. 

## Authentication
The RestServices module only supports *Basic authentication* out of the box. But it also possible to authenticate using OAuth or other security protocols by following the specs of the designated service. (There is a working Dropbox integration app based on a combination of the RestServices module and the Deeplink module for requesting tokens). 

Basic authentication credentials can be send by using `addCredentialsToNextRequest` just before the actual request is made. Too make life easier, it is also possible to use `registerCredentials`, which will send credentials with *any* subsequent request to the same host. 

## Other methods
For all standard HTTP verbs there is a method available which wraps the `request` operation, but simplifies the arguments one has to provide. See the 'HTTP Verbs' section for best practices about when to use which verb. 

### `get`
Tries to retrieve an object from the provided URL. Expects JSON data which will be parsed into the `stub` object. A `stub` object *should* be a just created, empty and transient object. This object will be filled as described in the 'JSON Deserialization' section. 

### `getCollection`
Tries to retrieve a list of objects from the provided URL. The expected response body is either an array of JSON objects, or an array of URLs, which will be retrieved automatically to retrieve all the items in the array. `getCollection` uses streaming to make the process as memory efficient as possible. 

The `resultList` parameter should be a completely empty list, but of the same type as `firstResult`. The `firstResult` object will be used to story the first result in and should be a new, empty, transient object. 

<small>Needing to provide both the `resultList` and `firstResult` parameters might seem a bit awkward, but the `firstResult` object is required by the actual implementation to determine the type of the result set, because lists in Mendix are untyped at runtime)</small>


### `getCollectionAsync`
The behavior is similar to `getCollection`, but its implementation is very suitable for very large response sets. Instead of building a list, the `callbackMicroflow` will be invoked on each individual item in the response array. This way, only one item is in memory of the server at any given time. 

### `post`
Submits an object to the remote server. The `dataObject` will be serialized to JSON as described in the 'JSON Serialization' section. If the `asFormData` parameter is set, the data will not be encoded as JSON but as form data (which is commonly used for submitting web forms for example). Note that form data only supports flat, primitive objects. 

### `delete`
Tries to delete a resource at the given URL. 

### `put`
Similar to `post`. See the 'HTTP verbs' section or the specs of the service you are consuming to find out whether to use `post` or `put`. 

# Publishing REST services

Publishing a REST service is pretty straight forward with this modules. The module provides publishing REST services in two flavors:

1. Publishing operations, based on a single microflow. 
2. Publishing a part of your data model, and providing a typical rest based API to retrieve, update, delete, create and even real-time sync data. 

PLEASE NOT THAT TO BE ABLE TO PUBLISH ANY SERVICE, THE MICROFLOW `STARTPUBLISHSERVICES` SHOULD BE CALLED DURING STARTUP OF THE APP!

## Publishing a microflow using `CreateMicroflowService`
Publishing a microflow is conceptually very similar to publishing a webservice. It publishing a single operation based on a microflow. The difference with a normal Mendix webservice is the transport method; instead of SOAP the RestServices module provides an interface which supports JSON based messages or form / multipart encoded messages (typically used to submit webforms). 

A published microflow should have a single transient object as argument. Each field in this transient object is considered a parameter (from HTTP perspective). Complex objects are supported if JSON is used as transport mechanism. The return type of the microflow should again be a transient object or a String. In the latter case, the string is considered to be the raw response of the operation which is not further processed. 

Publishing a microflow is as simple as calling `CreateMicroflowService` with the public name of the operation and the microflow that provides the implementation. The meta data of the operation is published on *&lt;app-url&gt;/rest/*, including a [JSON-schema](http://json-schema.org/) describing its arguments. The endpoint for the operation itself is *&lt;app-url&gt;/rest/&lt;public-name&gt;*

## Publishing a data service

### Introduction

A Published Service provides a JSON based API to list, retrieve, create, update, delete and track objects in your database. Documentation for these services will be generated automatically by the module and can be read by pointing your browser at *&lt;app-url&gt;/rest/*.

The central idea behind a service that there is a persistent entity in your database acting as data *source* for your service. Furthermore your model should define a transient object that will act as *view* object of your data, so that your internal data structure is not directly published to the outside. This allows for better maintainability and it guarantees that you can pre- or post-process your data when required. 

When publishing data, the *Publish Microflow* has the responsibility of converting *source* objects into *view objects*. When processing incoming data, the *Update Microflow* is responsible for exactly the reverse process; transforming a *view* as provided by some consumer into real data in your database. 

Each *source* object should be uniquely identifiable by a single *key* attribute. 

For example: *GET &lt;your-app&gt;/rest/&lt;service name&gt;/&lt;identifier&gt;* will search for the first *source* object in your database which *key* value equals the *identifier*. If found, this object will be converted by the *Publish Microflow* into a *view object*. This *view object* will be serialized to JSON (or HTML or XML) and returned to the consumer.  

### Creating a new service
Creating a new JSON based data service with full CRUD support is pretty straightforward with the RestServices module. The easiest way to start is to connect the microflow `IVK_OpenServiceOverview` to your navigation and create a new Published Service after starting your app. 

Once you have figured out the correct configuration for your data service, a best practice is to use the `GetOrCreateDataService` microflow in the startup microflow to create your service configuration. Alter and commit the properties of the resulting ServiceDefinition to update the configuration. If the configuration contains consistency errors, those will will be listed in the log of your application. 

### Configuring your service

You can further configure the service from either a microflow or the form in the running app by setting the following properties.

#### Core configuration

Property | Default value | Description
-------- | ------------- | ----------
Name | (required) | Public name of this service. The endpoint of the service will be *&lt;app-url&gt;/rest/&lt;name&gt;*. Example: `Tasks`
Description | (optional) | Description of this services. Will be part of the generated meta data
Source Entity | (required) | The entity in the data model that should be published. Should be persistable. Example: `MyModule.Task`
Source Key Attribute | (required) | Attribute that uniquly identies an object and which will be used as external reference to this object. The attribute should not change over time. Example: `TaskID`
Source Constraint | (optional) | XPath constraint that limits which objects are readable / writable through this service. It is possible to use the `'[%Current_User%]'` token inside this constraint, unless the *Enable Change Tracking* flag is set. Example: `[Finished = false() and MyModule.Task_Owner = '[%Current_User%]']`
Required Role | `*` | Set this property to indicate that authentication is required to use this service. Exactly one project wide role can be specified. If set, the consumer is required to authenticate using Basic Authentication if the consumer doesn't have a normal Mendix client session. Use `*` to make the service world readable / writable.
On Publish Microflow | (optional) | Microflow that transforms a *source object* into some transient object which (the *view*). This view will be serialized into JSON/HTML/XML when data is requested from the service. The speed of this microflow primarily determines the speed of the service as a whole. 
On Update Microflow | (optional) | Microflow that processes incoming changes. Should have two parameters; one of same type as the *source entity*, and one which is a transient entity. The transient object will be constructed with JSON data in the incoming request. This transient object should be processed by the microflow and update the *source* object as desired. Use the `ThrowWebserviceException` method of Community Commons to signal any exceptions to the consumer. 
On Delete Microflow | (optional) | Similar to the *On Update Microflow* but takes a String attribute as argument, that represents the *key* of the object that should be deleted

#### Features

Property | Default value | Description
-------- | ------------- | ----------
Enable GET | `true` | Allows requesting individual objects using GET at *rest/servicename/objectid*
Enable Listing | `true` | Allows listing all available objects using GET at *rest/servicename/*
Enable Update | `false` | Allows consumers to update existing objects using PUT or POST at *rest/servicename/objectid*
Enable Create | `false` | Allows consumers to create new objects using POST at *rest/servicename/*
Enable Delete | `false` | Allows consumers to remove existing objects using DELETE at *rest/servicename/objectid*
Enable Change Tracking | `false` | See next section
Use Automatic Change Tracking | `false` | TODO
Use Strict Versioning | `false` | If set to true, all requests that modify data are required to provide an `if-none-match` header, to verify that the request is based on the latest known version. This way conflicting updates are detected and it is not possible to base changes on stale objects

#### Enable Change Tracking

The *Enable Change Tracking* property has significant impact on the behavior and internal working of the service. It introduces a cache in which the JSON representation of each objects are stored and provides the possibility to synchronize with consumers over time. See the [Data synchronisation](Data synchronisation) section for more details. Enabling change tracking has the following consequences:

* Two new endpoints are created: *rest/service/changes/list* and *rest/service/changes/feed*. Both endpoints provide a list of all changes which where made to the *source* collection. The endpoints accept an `rev` parameter, which can be used to only retrieve changes which were not synced yet. The API and behavior are heavily inspired by the [CouchDB changes API](TODO). 
* Requests are served from the cache instead of the database directly. To update an item in the cache, the model needs to call `publishUpdate` or `publishDelete`. Changes are not visible for consumers until one of these methods is called by the model. 
* The performance of retrieving objects is improved, since they are stored in serialized form internally. 
* If, for example, the domain model of your *source* or *view* object changes, the cache becomes stale. Most model changes are detected by the RestServices module automatically, but you can force rebuilding the complete index by invoking `RebuildServiceIndex`. 

### Using your service

Responses can also be rendered as XML or HTML, depending on the Accept headers of the request. 

# Data synchronization

Enable change tracking

# HTTP Verbs in Rest

# JSON Serialization

# JSON Deserialization

# Known Integrations

We know that the RestSevices module has already been used successfully to integrate with the following services:

* Dropbox.com
* Paydro.net
* Postcodeapi.nu
* Rijksmuseum.nl

# Development notes

* Feel free to fork and send pull requests! 
* Note that this is a GitHub repository which is based on git, in contrast to the Mendix TeamServer, which is based on SVN. So the build-in Teamserver support of the Mendix Modeler will not work for this repository! 
* If you want to receive access to the Mendix Project in which this module is managed, feel to request me an invite! The backlog of the project is also managed there. 
* Unit tests are defined in the *Tests* module. Those can be run in the usual way using the already included *[UnitTesting](https://appstore.mendix.com/app/Unit%20Testing)* module
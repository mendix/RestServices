# Publishing Resources

## Introduction
Resources published by the RestServices module always describe exactly one persistable entity type in your domain model. However, these entities are not directly exposed to the outer world, but via an intermediate view entity. 

Creating and publishing resources is done via two simple steps. 
1. Connect the microflow 'RestServices.StartPublishServices' to the startup microflow of your model.
2. Write a service definition as a `servicename.json` file in the folder `resources\RestServices\Published`. A service definition consists of a plain json object with the properties as defined in the next section. 

## Service definition properties

### servicename
An identifier describing the name of your services. All api endpoints of this service will be published under this name, for example if a service is named `users`, a single user record will be available under `<application root url>/rest/users/<identifier>`

### sourceentity
The entity you want to publish with this service. Note that no attributes except for the key attribute will be exposed directly to the consumers. The entity *must* be persistent. E.g.: `System.User`

### idattribute
The attribute (non-empty, unique) identifiers a specific resource. The URI of a resource will be based on this idattribute. For example idattribute `Name` for `System.User` will result in URI's like `<application root url>/rest/users/MxAdmin`

### constraint
Optional Xpath constraint which must be met by an instance before it can be published throught this service. For example `[Active=true()]`

### publishentity
The 'public interface' of the source entity. The publishentity is the object that will be exposed to the outer world. All its attributes will be serialized under their given name

### publishmicroflow
The microflow that maps a sourceentity onto a publishentity. 

## Exposed http api

## GET /rest/
List all available services and their meta information. (TODO)

## GET /rest/<service name>
Lists all available URI's in this service. Optional arguments:

* `contenttype` paramater which should be one of the following values: `json`, `xml` or `html`. Determines the output format of the service. 
* `content-type` header. Same as `contenttype` parameter. If both are provided the paramater is used, but in normal cases the header should be used, the parameter is for testing purposes only. 
* (suggested optimization) `data` boolean parameter, by default `false`. If `true`, complete objects are returned in instead of just the URI. Note that this might result in very large and longrunning responses. 
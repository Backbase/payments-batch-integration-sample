Example code for payments batch integration service. Goes with (newer version of) 
https://community.backbase.com/documentation/DBS/latest/batches_implement_integration_service

# payments-batch-integration-sample

Simple example implementation of a payment batch integration service. It receives payment batch orders from the Backbase payment-order-service and temporary stores them in a simple in-memory queue. It processes these payments on a schedule to call back to payment-order-service to update the status of the batch order.

## Dependencies

Requires a running Eureka registry, by default on port 8080.

## Configuration

Service configuration is under `src/main/resources/application.yml`.

## Running

To run the service in development mode, use:
- `mvn spring-boot:run`

To run the service from the built binaries, use:
- `java -jar target/payments-batch-integration-sample-1.0.1-SNAPSHOT.war`

## Authorization

This service uses service-2-service authentication on its receiving endpoints. It assumes mTLS is *turned off* on payment-order-service.

## Todo

* Add mTLS security.

# Use the Eclipse Temurin JRE 21 base image
FROM eclipse-temurin:21-jre-alpine

# Create a non-root user and group to run the application
RUN addgroup -S spring && adduser -S spring -G spring

# Set the working directory in the container
WORKDIR /app

# Copy the application JAR file into the container and set ownership to the non-root user
COPY --chown=spring:spring target/*.jar app.jar

# Switch to the non-root user
USER spring:spring

# Expose the application port
EXPOSE 8080

# Set the entry point to run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
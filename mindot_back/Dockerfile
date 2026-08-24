# ===== Build stage =====
FROM amazoncorretto:17 AS build
WORKDIR /app
COPY . .
# gradlew 실행 권한 추가
RUN chmod 777 ./gradlew

# Gradle은 보통 빌드 속도를 높이려고 백그라운드 데몬 프로세스를 띄움.
# 데몬 안띄우게 하는 것임. -> 속도 빨라짐
#RUN ./gradlew clean bootJar --no-daemon
RUN ./gradlew build -x test --no-daemon

# ===== Run stage =====
FROM amazoncorretto:17
WORKDIR /app
COPY --from=build /app/build/libs/*.jar /app/server.jar
EXPOSE 8080
# 컨테이너 안의 환경 변수를 정의하는 구문
# 컨테이너가 실행될 때 리눅스 환경 변수로 들어감.
ENV SPRING_PROFILES_ACTIVE=prod
CMD ["java", "-jar", "/app/server.jar"]

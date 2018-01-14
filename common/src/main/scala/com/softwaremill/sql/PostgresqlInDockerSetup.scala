package com.softwaremill.sql

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer

trait PostgresqlInDockerSetup extends DbSetup {

  private val postgres: PostgreSQLContainer[Nothing] =
    new PostgreSQLContainer()

  def jdbcUrl: String = postgres.getJdbcUrl
  def username: String = postgres.getUsername
  def passwrod: String = postgres.getPassword

  override def dbSetup(): Unit = {
    start()

    val flyway = new Flyway()
    flyway.setDataSource(jdbcUrl, username, passwrod)
    flyway.clean()
    flyway.migrate()
  }

  def start() = {
    postgres.start()
    println(
      s"url: ${postgres.getJdbcUrl}, username: ${postgres.getUsername}, password: ${postgres.getPassword}")
  }

  def stop() =
    postgres.stop()

}

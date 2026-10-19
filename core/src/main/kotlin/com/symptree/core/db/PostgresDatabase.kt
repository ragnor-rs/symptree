package com.symptree.core.db

import com.symptree.core.config.CoreConfig
import java.sql.Connection
import java.sql.DriverManager

class PostgresDatabase(private val config: CoreConfig) {
    init {
        Class.forName("org.postgresql.Driver")
    }

    fun getConnection(): Connection {
        val url = "jdbc:postgresql://${config.postgresHost}:${config.postgresPort}/${config.postgresDb}"
        return DriverManager.getConnection(url, config.postgresUser, config.postgresPassword)
    }
}

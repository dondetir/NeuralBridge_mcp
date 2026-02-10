/*!
 * Connection Pool
 *
 * Manages a pool of device connections with lifecycle management,
 * health checking, and automatic reconnection.
 */

use anyhow::{Result, Context};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{info, debug, warn};

use crate::protocol::connection::DeviceConnection;

/// Connection pool for managing multiple device connections
pub struct ConnectionPool {
    /// Map of device_id to connection
    connections: Arc<RwLock<HashMap<String, PooledConnection>>>,
}

/// Pooled connection with health tracking
#[allow(dead_code)]
struct PooledConnection {
    /// The actual connection
    connection: DeviceConnection,

    /// Number of active references
    ref_count: usize,

    /// Last health check timestamp
    last_health_check: std::time::Instant,
}

impl ConnectionPool {
    /// Create new connection pool
    pub fn new() -> Self {
        Self {
            connections: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Get or create a connection for a device
    #[allow(dead_code)]
    pub async fn get_connection(&self, device_id: &str) -> Result<DeviceConnection> {
        // Check if connection exists
        {
            let mut pool = self.connections.write().await;

            if let Some(pooled) = pool.get_mut(device_id) {
                // Check if connection is still alive
                if pooled.connection.is_alive().await {
                    pooled.ref_count += 1;
                    pooled.last_health_check = std::time::Instant::now();
                    debug!("Reusing existing connection for device {}", device_id);
                    return Ok(pooled.connection.clone());
                } else {
                    warn!("Connection for device {} is dead, removing from pool", device_id);
                    pool.remove(device_id);
                }
            }
        }

        // No existing connection, create new one
        info!("Creating new connection for device {}", device_id);
        let connection = DeviceConnection::connect().await
            .context("Failed to establish connection")?;

        // Add to pool
        {
            let mut pool = self.connections.write().await;
            pool.insert(device_id.to_string(), PooledConnection {
                connection: connection.clone(),
                ref_count: 1,
                last_health_check: std::time::Instant::now(),
            });
        }

        Ok(connection)
    }

    /// Release a connection (decrement ref count)
    #[allow(dead_code)]
    pub async fn release_connection(&self, device_id: &str) {
        let mut pool = self.connections.write().await;

        if let Some(pooled) = pool.get_mut(device_id) {
            pooled.ref_count = pooled.ref_count.saturating_sub(1);
            debug!("Released connection for device {}, ref_count={}", device_id, pooled.ref_count);
        }
    }

    /// Remove a connection from the pool
    #[allow(dead_code)]
    pub async fn remove_connection(&self, device_id: &str) -> Result<()> {
        let mut pool = self.connections.write().await;

        if let Some(pooled) = pool.remove(device_id) {
            info!("Removing connection for device {}", device_id);
            pooled.connection.close().await?;
        }

        Ok(())
    }

    /// Perform health check on all connections
    #[allow(dead_code)]
    pub async fn health_check(&self) -> Result<()> {
        debug!("Performing health check on connection pool");

        let mut pool = self.connections.write().await;
        let mut to_remove = Vec::new();

        for (device_id, pooled) in pool.iter() {
            // Check if connection is still alive
            if !pooled.connection.is_alive().await {
                warn!("Connection for device {} failed health check", device_id);
                to_remove.push(device_id.clone());
            } else {
                debug!("Connection for device {} is healthy", device_id);
            }
        }

        // Remove dead connections
        for device_id in to_remove {
            if let Some(pooled) = pool.remove(&device_id) {
                let _ = pooled.connection.close().await;
            }
        }

        Ok(())
    }

    /// Get pool statistics
    #[allow(dead_code)]
    pub async fn stats(&self) -> PoolStats {
        let pool = self.connections.read().await;

        PoolStats {
            total_connections: pool.len(),
            active_connections: pool.values().filter(|p| p.ref_count > 0).count(),
        }
    }

    /// Clear all connections
    #[allow(dead_code)]
    pub async fn clear(&self) -> Result<()> {
        info!("Clearing connection pool");

        let mut pool = self.connections.write().await;

        for (device_id, pooled) in pool.drain() {
            info!("Closing connection for device {}", device_id);
            let _ = pooled.connection.close().await;
        }

        Ok(())
    }
}

impl Default for ConnectionPool {
    fn default() -> Self {
        Self::new()
    }
}

/// Connection pool statistics
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct PoolStats {
    pub total_connections: usize,
    pub active_connections: usize,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_connection_pool_creation() {
        let pool = ConnectionPool::new();
        let stats = pool.stats().await;
        assert_eq!(stats.total_connections, 0);
        assert_eq!(stats.active_connections, 0);
    }

    #[tokio::test]
    async fn test_connection_pool_stats() {
        let pool = ConnectionPool::new();

        // Pool starts empty
        let stats = pool.stats().await;
        assert_eq!(stats.total_connections, 0);

        // TODO: Add more tests when connection establishment is implemented
    }
}

"""Performance measurement utilities."""

import time
from contextlib import asynccontextmanager
from typing import AsyncIterator, Dict, List, Optional

from rich.table import Table

from .logger import console


class LatencyTracker:
    """Track operation latencies for performance analysis."""

    def __init__(self):
        self.measurements: Dict[str, List[float]] = {}

    def record(self, operation: str, latency_ms: float) -> None:
        """Record a latency measurement.

        Args:
            operation: Name of the operation
            latency_ms: Latency in milliseconds
        """
        if operation not in self.measurements:
            self.measurements[operation] = []
        self.measurements[operation].append(latency_ms)

    def get_stats(self, operation: str) -> Optional[Dict[str, float]]:
        """Get statistics for an operation.

        Args:
            operation: Name of the operation

        Returns:
            Dictionary with avg, p50, p95, p99, min, max
        """
        if operation not in self.measurements or not self.measurements[operation]:
            return None

        values = sorted(self.measurements[operation])
        count = len(values)

        def percentile(p: float) -> float:
            idx = int(count * p)
            return values[min(idx, count - 1)]

        return {
            "avg": sum(values) / count,
            "p50": percentile(0.50),
            "p95": percentile(0.95),
            "p99": percentile(0.99),
            "min": values[0],
            "max": values[-1],
            "count": count
        }

    def get_all_stats(self) -> Dict[str, Dict[str, float]]:
        """Get statistics for all operations."""
        return {op: self.get_stats(op) for op in self.measurements if self.get_stats(op)}

    def print_summary(self, title: str = "Performance Summary") -> None:
        """Print performance summary table."""
        if not self.measurements:
            console.print("[yellow]No performance data collected[/yellow]")
            return

        table = Table(title=title, show_header=True, header_style="bold cyan")
        table.add_column("Operation", style="green")
        table.add_column("Avg (ms)", justify="right")
        table.add_column("P50 (ms)", justify="right")
        table.add_column("P95 (ms)", justify="right")
        table.add_column("P99 (ms)", justify="right")
        table.add_column("Count", justify="right")

        all_stats = self.get_all_stats()
        for operation, stats in sorted(all_stats.items()):
            table.add_row(
                operation,
                f"{stats['avg']:.1f}",
                f"{stats['p50']:.1f}",
                f"{stats['p95']:.1f}",
                f"{stats['p99']:.1f}",
                str(stats['count'])
            )

        console.print(table)

    def clear(self) -> None:
        """Clear all measurements."""
        self.measurements.clear()


@asynccontextmanager
async def measure_latency(
    tracker: LatencyTracker,
    operation: str
) -> AsyncIterator[None]:
    """Context manager to measure operation latency.

    Args:
        tracker: LatencyTracker to record measurement
        operation: Name of the operation

    Example:
        ```python
        tracker = LatencyTracker()
        async with measure_latency(tracker, "tap"):
            await client.tap(100, 200)
        ```
    """
    start_time = time.perf_counter()
    try:
        yield
    finally:
        end_time = time.perf_counter()
        latency_ms = (end_time - start_time) * 1000
        tracker.record(operation, latency_ms)


def format_latency(latency_ms: float, threshold_ms: float = 100.0) -> str:
    """Format latency with color based on threshold.

    Args:
        latency_ms: Latency in milliseconds
        threshold_ms: Threshold for warning (default 100ms)

    Returns:
        Formatted string with rich markup
    """
    if latency_ms < threshold_ms:
        return f"[green]{latency_ms:.1f}ms[/green]"
    else:
        return f"[red]{latency_ms:.1f}ms[/red]"

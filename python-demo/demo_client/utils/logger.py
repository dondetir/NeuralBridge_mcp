"""Structured logging utilities for NeuralBridge demo client."""

import logging
import sys
from typing import Optional

from rich.console import Console
from rich.logging import RichHandler


def setup_logger(
    name: str = "neuralbridge",
    level: str = "INFO",
    enable_rich: bool = True
) -> logging.Logger:
    """Setup structured logger with rich formatting.

    Args:
        name: Logger name
        level: Log level (DEBUG, INFO, WARNING, ERROR)
        enable_rich: Use rich terminal formatting

    Returns:
        Configured logger
    """
    logger = logging.getLogger(name)
    logger.setLevel(getattr(logging, level.upper()))

    # Remove existing handlers
    logger.handlers.clear()

    if enable_rich:
        # Rich handler for beautiful terminal output
        handler = RichHandler(
            rich_tracebacks=True,
            markup=True,
            show_time=True,
            show_path=False
        )
    else:
        # Standard stream handler
        handler = logging.StreamHandler(sys.stdout)
        formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            datefmt='%H:%M:%S'
        )
        handler.setFormatter(formatter)

    logger.addHandler(handler)
    return logger


# Global console for rich output
console = Console()

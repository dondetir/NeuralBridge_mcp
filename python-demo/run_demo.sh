#!/bin/bash
# Quick run script for NeuralBridge Python Demo

# Activate virtual environment
if [ ! -d "venv" ]; then
    echo "Virtual environment not found. Run ./setup.sh first."
    exit 1
fi

source venv/bin/activate

# Run demo
python -m demo_client.main "$@"

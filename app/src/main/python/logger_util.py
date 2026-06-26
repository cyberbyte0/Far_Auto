import sys
import time

_original_stdout = sys.stdout
_original_stderr = sys.stderr

class AndroidLogger:
    def __init__(self, log_callback, original_stream):
        self.log_callback = log_callback
        self.terminal = original_stream
        self.buffer = ""

    def write(self, message):
        if message:
            self.buffer += message
            while "\n" in self.buffer:
                line, self.buffer = self.buffer.split("\n", 1)
                self.log_callback(line)
        self.terminal.write(message)

    def flush(self):
        self.terminal.flush()

class AndroidInput:
    def __init__(self, input_provider):
        self.input_provider = input_provider

    def readline(self):
        while True:
            # check_stop() raises automator.ScriptStopped (a KeyboardInterrupt /
            # BaseException) on stop; let it propagate so a blocked input() unblocks.
            import automator
            automator.check_stop()

            val = self.input_provider()
            if val is not None:
                return val + "\n"
            time.sleep(0.5)

def setup_logger(log_callback, input_provider):
    sys.stdout = AndroidLogger(log_callback, _original_stdout)
    sys.stderr = AndroidLogger(log_callback, _original_stderr)
    sys.stdin = AndroidInput(input_provider)

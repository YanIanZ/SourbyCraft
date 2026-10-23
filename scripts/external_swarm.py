"""A client swarm driven by an external program (SoulFire) instead of baseline_client.

bench_panel.py normally runs baseline_client.ClientSwarm in-process.  `--external-clients-cmd`
replaces it with any command that joins the bench clients to the server (for example SoulFire's
CLI with an offline account file naming Bench000..); this class starts it in its own session,
counts the clients in play from the server's own player list, and stops the process group when
the bench ends or aborts.  It has the three methods bench_panel uses: start, report, stop.
"""
import os
import signal
import subprocess
import time


class ExternalSwarm:
    def __init__(self, argv, requested, online_count, log_path, grace=30.0,
                 sleep=time.sleep, clock=time.monotonic):
        self.argv, self.requested, self.online_count = list(argv), requested, online_count
        self.log_path, self.grace, self._sleep, self._clock = log_path, grace, sleep, clock
        self.process = None
        self._log = None

    def start(self, timeout=600.0):
        """Launch the command and wait until every client is in play, it exits, or timeout passes."""
        self._log = open(self.log_path, "ab")
        self.process = subprocess.Popen(self.argv, stdout=self._log, stderr=subprocess.STDOUT,
                                        stdin=subprocess.DEVNULL, start_new_session=True)
        deadline = self._clock() + timeout
        while self._clock() < deadline:
            if self.process.poll() is not None or self.online_count() >= self.requested:
                break
            self._sleep(5)
        return self.report()

    def report(self):
        alive = self.process is not None and self.process.poll() is None
        return {"requested": self.requested, "in_play": self.online_count() if alive else 0,
                "process_alive": alive,
                "exit_code": None if self.process is None else self.process.poll()}

    def stop(self):
        """SIGTERM the whole process group, SIGKILL it after the grace period; safe to call twice."""
        process = self.process
        if process is not None and process.poll() is None:
            self._signal(process, signal.SIGTERM)
            deadline = self._clock() + self.grace
            while process.poll() is None and self._clock() < deadline:
                self._sleep(0.5)
            if process.poll() is None:
                self._signal(process, signal.SIGKILL)
                process.wait()
        if self._log is not None:
            self._log.close()
            self._log = None

    @staticmethod
    def _signal(process, number):
        try:
            os.killpg(os.getpgid(process.pid), number)
        except ProcessLookupError:
            pass

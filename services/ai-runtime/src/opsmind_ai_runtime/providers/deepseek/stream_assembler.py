"""Ordered streaming state machine for complete provider exchanges."""

from __future__ import annotations

from dataclasses import dataclass, field


class IncompleteStream(ValueError):
    """Raised when a stream ends before a terminal frame."""


class InvalidStreamFrame(ValueError):
    """Raised for duplicate, out-of-order, or contradictory frames."""


@dataclass(slots=True)
class StreamAssembler:
    """Assemble content/tool fragments without accepting partial conclusions."""

    _next_index: int = 0
    _terminal: str | None = None
    _content: list[str] = field(default_factory=list)
    _tool_fragments: list[str] = field(default_factory=list)

    def push(
        self,
        *,
        index: int,
        content: str | None = None,
        tool_fragment: str | None = None,
        finish_reason: str | None = None,
    ) -> None:
        if self._terminal is not None:
            raise InvalidStreamFrame("frame arrived after terminal frame")
        if index != self._next_index:
            raise InvalidStreamFrame("stream frame index is not contiguous")
        self._next_index += 1
        if content:
            self._content.append(content)
        if tool_fragment:
            self._tool_fragments.append(tool_fragment)
        if finish_reason is not None:
            if finish_reason not in {"stop", "tool_calls"}:
                raise InvalidStreamFrame("unsupported terminal reason")
            self._terminal = finish_reason

    def finalize(self) -> tuple[str, str, str]:
        if self._terminal is None:
            raise IncompleteStream("stream has no terminal frame")
        return self._terminal, "".join(self._content), "".join(self._tool_fragments)

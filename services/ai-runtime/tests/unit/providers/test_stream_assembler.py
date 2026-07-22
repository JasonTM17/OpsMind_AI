import pytest

from opsmind_ai_runtime.providers.deepseek.stream_assembler import (
    IncompleteStream,
    InvalidStreamFrame,
    StreamAssembler,
)


def test_stream_requires_terminal_frame() -> None:
    assembler = StreamAssembler()
    assembler.push(index=0, content="{")
    with pytest.raises(IncompleteStream):
        assembler.finalize()


def test_stream_assembles_only_contiguous_complete_frames() -> None:
    assembler = StreamAssembler()
    assembler.push(index=0, content="{")
    assembler.push(index=1, content="}", finish_reason="stop")
    assert assembler.finalize() == ("stop", "{}", "")
    with pytest.raises(InvalidStreamFrame):
        assembler.push(index=2, content="late")


def test_stream_rejects_out_of_order_frame() -> None:
    assembler = StreamAssembler()
    with pytest.raises(InvalidStreamFrame):
        assembler.push(index=1, content="out-of-order")

.DEFAULT_GOAL := help

.PHONY: help setup dev test lint build up down migrate seed evaluate security security-scan doctor

help setup dev test lint build up down migrate seed evaluate security security-scan doctor:
	@sh ./scripts/dev/opsmind.sh $@

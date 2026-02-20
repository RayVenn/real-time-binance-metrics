from typing import Optional

import numpy as np
import pandas as pd
from pypfopt import objective_functions
from pypfopt.efficient_frontier import EfficientFrontier

from app.models.portfolio import (
    OptimizationResult,
    PortfolioConstraints,
    PortfolioRequest,
)


def _get_weight_bounds(constraints: Optional[PortfolioConstraints]) -> tuple:
    """Build weight bounds from constraints."""
    if constraints is None:
        return (0, 1)

    if constraints.weight_bounds is not None:
        return constraints.weight_bounds

    return (constraints.min_weight, constraints.max_weight)


def _create_efficient_frontier(request: PortfolioRequest) -> EfficientFrontier:
    """Create and configure EfficientFrontier with constraints and costs."""
    tickers = request.tickers

    # Use pandas Series/DataFrame for proper ticker mapping with sector constraints
    mu = pd.Series(request.expected_returns, index=tickers)
    cov = pd.DataFrame(request.covariance_matrix, index=tickers, columns=tickers)

    weight_bounds = _get_weight_bounds(request.constraints)
    ef = EfficientFrontier(mu, cov, weight_bounds=weight_bounds)

    # Add sector constraints if provided
    if request.constraints and request.constraints.sector_mapper:
        ef.add_sector_constraints(
            sector_mapper=request.constraints.sector_mapper,
            sector_lower=request.constraints.sector_lower or {},
            sector_upper=request.constraints.sector_upper or {},
        )

    # Add transaction costs if provided
    if request.transaction_costs:
        tc = request.transaction_costs
        if tc.current_weights is None:
            current_weights = np.zeros(len(tickers))
        else:
            current_weights = np.array(tc.current_weights)

        if tc.proportional:
            ef.add_objective(
                objective_functions.transaction_cost,
                w_prev=current_weights,
                k=tc.proportional,
            )

    return ef


def _build_result(
    ef: EfficientFrontier, tickers: list[str], risk_free_rate: float
) -> OptimizationResult:
    """Build OptimizationResult from optimized EfficientFrontier."""
    weights = ef.clean_weights()
    perf = ef.portfolio_performance(risk_free_rate=risk_free_rate)

    return OptimizationResult(
        weights={ticker: weights[ticker] for ticker in tickers},
        expected_return=perf[0],
        volatility=perf[1],
        sharpe_ratio=perf[2],
    )


def optimize_max_sharpe(request: PortfolioRequest) -> OptimizationResult:
    """Optimize portfolio for maximum Sharpe ratio."""
    ef = _create_efficient_frontier(request)
    ef.max_sharpe(risk_free_rate=request.risk_free_rate)
    return _build_result(ef, request.tickers, request.risk_free_rate)


def optimize_min_volatility(request: PortfolioRequest) -> OptimizationResult:
    """Optimize portfolio for minimum volatility."""
    ef = _create_efficient_frontier(request)
    ef.min_volatility()
    return _build_result(ef, request.tickers, request.risk_free_rate)


def optimize_efficient_return(request: PortfolioRequest) -> OptimizationResult:
    """Optimize portfolio for a target return with minimum volatility."""
    if request.target_return is None:
        raise ValueError("target_return is required for efficient_return optimization")

    ef = _create_efficient_frontier(request)
    ef.efficient_return(target_return=request.target_return)
    return _build_result(ef, request.tickers, request.risk_free_rate)


def optimize_efficient_risk(request: PortfolioRequest) -> OptimizationResult:
    """Optimize portfolio for a target volatility with maximum return."""
    if request.target_volatility is None:
        raise ValueError(
            "target_volatility is required for efficient_risk optimization"
        )

    ef = _create_efficient_frontier(request)
    ef.efficient_risk(target_volatility=request.target_volatility)
    return _build_result(ef, request.tickers, request.risk_free_rate)

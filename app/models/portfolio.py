from typing import Optional

from pydantic import BaseModel, Field


class Asset(BaseModel):
    ticker: str
    expected_return: float = Field(..., description="Expected annual return")


class TransactionCosts(BaseModel):
    """Transaction cost model configuration."""

    proportional: Optional[float] = Field(
        default=None,
        description="Proportional transaction cost (e.g., 0.001 for 0.1% per trade)",
    )
    flat: Optional[float] = Field(
        default=None, description="Flat transaction cost per asset traded"
    )
    current_weights: Optional[list[float]] = Field(
        default=None,
        description="Current portfolio weights (required for transaction cost calculation)",
    )


class PortfolioConstraints(BaseModel):
    """Portfolio constraints configuration."""

    min_weight: float = Field(
        default=0.0,
        description="Minimum weight per asset (0 = long-only, negative = allow shorts)",
    )
    max_weight: float = Field(default=1.0, description="Maximum weight per asset")
    weight_bounds: Optional[list[tuple[float, float]]] = Field(
        default=None, description="Per-asset weight bounds as list of (min, max) tuples"
    )
    sector_mapper: Optional[dict[str, str]] = Field(
        default=None,
        description="Map ticker to sector (e.g., {'AAPL': 'Tech', 'JPM': 'Finance'})",
    )
    sector_lower: Optional[dict[str, float]] = Field(
        default=None, description="Minimum weight per sector (e.g., {'Tech': 0.1})"
    )
    sector_upper: Optional[dict[str, float]] = Field(
        default=None, description="Maximum weight per sector (e.g., {'Tech': 0.4})"
    )
    max_assets: Optional[int] = Field(
        default=None,
        description="Maximum number of assets in portfolio (cardinality constraint)",
    )


class PortfolioRequest(BaseModel):
    tickers: list[str] = Field(..., description="List of asset tickers")
    expected_returns: list[float] = Field(
        ..., description="Expected returns for each asset"
    )
    covariance_matrix: list[list[float]] = Field(
        ..., description="Covariance matrix of returns"
    )
    risk_free_rate: float = Field(default=0.02, description="Risk-free rate")
    target_return: Optional[float] = Field(
        default=None, description="Target return for optimization"
    )
    target_volatility: Optional[float] = Field(
        default=None, description="Target volatility for optimization"
    )
    transaction_costs: Optional[TransactionCosts] = Field(
        default=None, description="Transaction cost model"
    )
    constraints: Optional[PortfolioConstraints] = Field(
        default=None, description="Portfolio constraints"
    )


class OptimizationResult(BaseModel):
    weights: dict[str, float] = Field(..., description="Optimal weights for each asset")
    expected_return: float = Field(..., description="Expected portfolio return")
    volatility: float = Field(..., description="Portfolio volatility (std dev)")
    sharpe_ratio: float = Field(..., description="Sharpe ratio")


class HealthResponse(BaseModel):
    status: str
    version: str
